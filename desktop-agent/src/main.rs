#[cfg(windows)]
mod dxgi_capture;
#[cfg(windows)]
mod windows_service;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    println!("OmniControl Desktop Agent v1.0.0 đã khởi động.");
    tokio::signal::ctrl_c().await?;
    Ok(())
}