use std::sync::atomic::{AtomicBool, Ordering};

static READY: AtomicBool = AtomicBool::new(false);

pub fn system_dns_ready() -> bool {
    READY.load(Ordering::SeqCst)
}

fn mark_ready() {
    READY.store(true, Ordering::SeqCst);
}

#[cfg(target_os = "android")]
mod jni_init {
    use std::ffi::c_void;

    use jni::{
        objects::{JClass, JObject},
        JNIEnv,
    };

    use super::mark_ready;

    /// Called from `NativeInit.installIrohDnsContext` with the Application.
    ///
    /// Keeps a global JNI ref so iroh can read `LinkProperties` DNS servers.
    #[no_mangle]
    pub extern "system" fn Java_dev_patchpipe_app_NativeInit_installIrohDnsContext(
        env: JNIEnv,
        _class: JClass,
        context: JObject,
    ) {
        let Ok(vm) = env.get_java_vm() else {
            tracing::error!("failed to get JavaVM for iroh DNS");
            return;
        };
        let Ok(global) = env.new_global_ref(&context) else {
            tracing::error!("failed to pin Android context for iroh DNS");
            return;
        };
        let context_ptr = global.as_obj().as_raw() as *mut c_void;
        std::mem::forget(global);
        unsafe {
            iroh::dns::install_android_jni_context(
                vm.get_java_vm_pointer() as *mut c_void,
                context_ptr,
            );
        }
        mark_ready();
    }
}
