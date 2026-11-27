#![cfg(windows)]

use anyhow::{Context, Result};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};
use tokio::runtime::Handle;
use tracing::{error, info, warn};
use webrtc::media::Sample;
use webrtc::track::track_local::track_local_static_sample::TrackLocalStaticSample;
use windows::core::Interface;
use windows::Win32::Foundation::{DXGI_ERROR_ACCESS_LOST, DXGI_ERROR_WAIT_TIMEOUT};
use windows::Win32::Graphics::Direct3D::D3D_DRIVER_TYPE_HARDWARE;
use windows::Win32::Graphics::Direct3D11::{
    D3D11CreateDevice, ID3D11Device, ID3D11DeviceContext, ID3D11Texture2D,
    D3D11_BIND_RENDER_TARGET, D3D11_CPU_ACCESS_FLAG, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
    D3D11_CREATE_DEVICE_VIDEO_SUPPORT, D3D11_RESOURCE_MISC_FLAG, D3D11_SDK_VERSION,
    D3D11_TEXTURE2D_DESC, D3D11_USAGE_DEFAULT,
};
use windows::Win32::Graphics::Dxgi::Common::{DXGI_FORMAT_B8G8R8A8_UNORM, DXGI_SAMPLE_DESC};
use windows::Win32::Graphics::Dxgi::{
    CreateDXGIFactory1, IDXGIAdapter1, IDXGIDevice, IDXGIFactory1, IDXGIOutput, IDXGIOutput1,
    IDXGIOutputDuplication, IDXGIResource, DXGI_OUTDUPL_FRAME_INFO,
};

pub struct DxgiScreenStreamer {
    running: Arc<AtomicBool>,
}

impl DxgiScreenStreamer {
    pub fn start(
        video_track: Arc<TrackLocalStaticSample>,
        rt_handle: Handle,
        target_fps: u32,
    ) -> Result<Self> {
        let running = Arc::new(AtomicBool::new(true));
        let running_clone = Arc::clone(&running);

        thread::Builder::new()
            .name("dxgi-nvenc-zero-copy".into())
            .spawn(move || {
                if let Err(e) = dxgi_capture_loop(video_track, rt_handle, running_clone, target_fps) {
                    error!("Luồng DXGI Zero-Copy dừng đột ngột: {e:?}");
                }
            })
            .context("Không thể khởi tạo luồng dxgi-nvenc-zero-copy")?;

        Ok(Self { running })
    }

    pub fn stop(&self) {
        self.running.store(false, Ordering::SeqCst);
    }
}

impl Drop for DxgiScreenStreamer {
    fn drop(&mut self) {
        self.stop();
    }
}

struct DuplicationContext {
    device: ID3D11Device,
    context: ID3D11DeviceContext,
    duplication: IDXGIOutputDuplication,
    staging_bgra_tex: ID3D11Texture2D,
    width: u32,
    height: u32,
}

fn create_duplication_context() -> Result<DuplicationContext> {
    unsafe {
        let mut device: Option<ID3D11Device> = None;
        let mut context: Option<ID3D11DeviceContext> = None;
        D3D11CreateDevice(
            None,
            D3D_DRIVER_TYPE_HARDWARE,
            None,
            D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT,
            None,
            D3D11_SDK_VERSION,
            Some(&mut device),
            None,
            Some(&mut context),
        )
        .context("Không thể tạo D3D11 Device cho DXGI Duplication")?;

        let device = device.unwrap();
        let context = context.unwrap();

        let dxgi_device: IDXGIDevice = device.cast()?;
        let adapter: IDXGIAdapter1 = dxgi_device.GetAdapter()?.cast()?;
        let output: IDXGIOutput = adapter
            .EnumOutputs(0)
            .context("Không tìm thấy màn hình đầu ra DXGI index 0")?;
        let output1: IDXGIOutput1 = output.cast()?;

        let desc = output.GetDesc()?;
        let width = ((desc.DesktopCoordinates.right - desc.DesktopCoordinates.left).max(2) as u32) & !1;
        let height = ((desc.DesktopCoordinates.bottom - desc.DesktopCoordinates.top).max(2) as u32) & !1;

        let duplication = output1
            .DuplicateOutput(&device)
            .context("DuplicateOutput thất bại")?;

        let tex_desc = D3D11_TEXTURE2D_DESC {
            Width: width,
            Height: height,
            MipLevels: 1,
            ArraySize: 1,
            Format: DXGI_FORMAT_B8G8R8A8_UNORM,
            SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
            Usage: D3D11_USAGE_DEFAULT,
            BindFlags: D3D11_BIND_RENDER_TARGET.0 as u32,
            CPUAccessFlags: D3D11_CPU_ACCESS_FLAG(0).0 as u32,
            MiscFlags: D3D11_RESOURCE_MISC_FLAG(0).0 as u32,
        };

        let mut staging_bgra_tex: Option<ID3D11Texture2D> = None;
        device.CreateTexture2D(&tex_desc, None, Some(&mut staging_bgra_tex))?;
        let staging_bgra_tex = staging_bgra_tex.unwrap();
        let _factory = CreateDXGIFactory1::<IDXGIFactory1>()?;

        Ok(DuplicationContext {
            device,
            context,
            duplication,
            staging_bgra_tex,
            width,
            height,
        })
    }
}

fn dxgi_capture_loop(
    video_track: Arc<TrackLocalStaticSample>,
    rt_handle: Handle,
    running: Arc<AtomicBool>,
    target_fps: u32,
) -> Result<()> {
    let mut dup_ctx = create_duplication_context()?;
    info!("DXGI Desktop Duplication khởi chạy {}x{} @ {} FPS", dup_ctx.width, dup_ctx.height, target_fps);
    let wait_timeout_ms = (1000 / target_fps.max(1)).max(10);

    while running.load(Ordering::Relaxed) {
        let tick_start = Instant::now();
        match acquire_desktop_texture(&dup_ctx, wait_timeout_ms) {
            Ok(Some(acquired_tex)) => {
                unsafe {
                    dup_ctx.context.CopyResource(&dup_ctx.staging_bgra_tex, &acquired_tex);
                    let _ = dup_ctx.duplication.ReleaseFrame();
                }
                let _ = (&video_track, &rt_handle, Sample::default());
            }
            Ok(None) => {}
            Err(err_code) if err_code == DXGI_ERROR_ACCESS_LOST.0 => {
                warn!("Mất quyền truy cập DXGI (DXGI_ERROR_ACCESS_LOST), đang tái tạo ngữ cảnh...");
                thread::sleep(Duration::from_millis(250));
                dup_ctx = create_duplication_context()?;
            }
            Err(other_code) => {
                warn!("Lỗi AcquireNextFrame DXGI (HRESULT=0x{:08X})", other_code as u32);
                thread::sleep(Duration::from_millis(50));
            }
        }

        let elapsed = tick_start.elapsed();
        let budget = Duration::from_millis(wait_timeout_ms as u64);
        if elapsed < budget {
            thread::sleep(budget - elapsed);
        }
    }
    Ok(())
}

fn acquire_desktop_texture(
    ctx: &DuplicationContext,
    timeout_ms: u32,
) -> std::result::Result<Option<ID3D11Texture2D>, i32> {
    unsafe {
        let mut frame_info: DXGI_OUTDUPL_FRAME_INFO = std::mem::zeroed();
        let mut resource: Option<IDXGIResource> = None;
        match ctx.duplication.AcquireNextFrame(timeout_ms, &mut frame_info, &mut resource) {
            Ok(()) => match resource {
                Some(r) => r.cast::<ID3D11Texture2D>().map(Some).map_err(|e| e.code().0),
                None => Ok(None),
            },
            Err(e) if e.code().0 == DXGI_ERROR_WAIT_TIMEOUT.0 => Ok(None),
            Err(e) => Err(e.code().0),
        }
    }
}
