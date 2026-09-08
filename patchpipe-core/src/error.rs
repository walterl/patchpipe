#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum PatchpipeError {
    #[error("{0}")]
    Message(String),
}

impl PatchpipeError {
    pub fn message(msg: impl Into<String>) -> Self {
        Self::Message(msg.into())
    }
}
