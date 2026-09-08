use std::str::FromStr;

use iroh::SecretKey;

use crate::error::PatchpipeError;

pub fn generate_secret_key() -> SecretKey {
    SecretKey::generate()
}

pub fn secret_key_hex(key: &SecretKey) -> String {
    data_encoding::HEXLOWER.encode(&key.to_bytes())
}

pub fn parse_secret_key(hex: &str) -> Result<SecretKey, PatchpipeError> {
    let trimmed = hex.trim();
    SecretKey::from_str(trimmed).map_err(|e| {
        PatchpipeError::message(format!("invalid secret key: {e}"))
    })
}

pub fn secret_key_from_optional(hex: Option<&str>) -> Result<SecretKey, PatchpipeError> {
    match hex {
        Some(h) if !h.trim().is_empty() => parse_secret_key(h),
        _ => Ok(generate_secret_key()),
    }
}
