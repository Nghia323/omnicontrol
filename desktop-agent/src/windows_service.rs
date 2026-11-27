#![cfg(windows)]

use anyhow::{Context, Result};
use std::ffi::OsString;
use windows::core::PCWSTR;
use windows::Win32::System::StationsAndDesktops::{
    GetThreadDesktop, OpenDesktopW, OpenInputDesktop, SetThreadDesktop, DESKTOP_CONTROL_HANGUL,
    DESKTOP_CREATEWINDOW, DESKTOP_HOOKCONTROL, DESKTOP_READOBJECTS, DESKTOP_SWITCHDESKTOP,
    DESKTOP_WRITEOBJECTS, DF_ALLOWOTHERACCOUNTHOOK,
};
use windows::Win32::System::Threading::GetCurrentThreadId;

pub fn attach_current_thread_to_input_desktop() -> Result<()> {
    unsafe {
        let access = DESKTOP_READOBJECTS
            | DESKTOP_WRITEOBJECTS
            | DESKTOP_CREATEWINDOW
            | DESKTOP_HOOKCONTROL
            | DESKTOP_CONTROL_HANGUL
            | DESKTOP_SWITCHDESKTOP;

        let input_desk = OpenInputDesktop(DF_ALLOWOTHERACCOUNTHOOK, false, access)
            .context("Không thể mở Input Desktop hiện tại")?;

        SetThreadDesktop(input_desk).context("SetThreadDesktop thất bại")?;
        Ok(())
    }
}

pub fn current_thread_desktop_name() -> String {
    unsafe {
        let tid = GetCurrentThreadId();
        if GetThreadDesktop(tid).is_err() {
            return "unknown".to_owned();
        }
        let _ = OpenDesktopW(
            PCWSTR::null(),
            DF_ALLOWOTHERACCOUNTHOOK,
            false,
            DESKTOP_READOBJECTS,
        );
        OsString::from("WinSta0\\Default").to_string_lossy().into_owned()
    }
}
