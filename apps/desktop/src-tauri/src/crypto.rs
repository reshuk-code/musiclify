//! Keys and encryption.
//!
//! * Songs: AES-128-CTR with a random media key. CTR is seekable, so the player can jump
//!   anywhere in a file and we decrypt just that range. The IV is derived from the song id.
//! * The media key lives in the OS credential store (Windows Credential Manager / macOS
//!   Keychain / Secret Service), never in the app's files.
//! * Device linking: AES-256-GCM with a one-time key carried in the QR code.

use crate::error::{Error, Result};
use aes::cipher::{KeyIvInit, StreamCipher, StreamCipherSeek};
use aes_gcm::aead::{Aead, AeadCore, KeyInit, OsRng};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::Engine;
use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use rand::RngCore;

type Aes128Ctr = ctr::Ctr128BE<aes::Aes128>;

const SERVICE: &str = "com.musiclify.desktop";
const MEDIA_KEY_ENTRY: &str = "media-key";

/// Load the media key from the OS credential store, creating it on first run.
pub fn load_or_create_media_key() -> Result<[u8; 16]> {
    let entry = keyring::Entry::new(SERVICE, MEDIA_KEY_ENTRY).map_err(|e| Error::msg(e.to_string()))?;
    if let Ok(stored) = entry.get_password() {
        if let Ok(bytes) = STANDARD.decode(stored) {
            if let Ok(key) = <[u8; 16]>::try_from(bytes.as_slice()) {
                return Ok(key);
            }
        }
    }
    let mut key = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut key);
    entry.set_password(&STANDARD.encode(key)).map_err(|e| Error::msg(e.to_string()))?;
    Ok(key)
}

/// Stream cipher positioned at byte `offset` of song `song_id`. Encrypt and decrypt are the same op.
pub fn song_cipher(key: &[u8; 16], song_id: i64, offset: u64) -> Aes128Ctr {
    let mut iv = [0u8; 16];
    iv[..8].copy_from_slice(&(song_id as u64).to_be_bytes());
    let mut c = Aes128Ctr::new(key.into(), &iv.into());
    c.seek(offset);
    c
}

pub fn apply(cipher: &mut Aes128Ctr, data: &mut [u8]) {
    cipher.apply_keystream(data);
}

// ---------------- device linking ----------------

pub fn new_link_key() -> [u8; 32] {
    let mut k = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut k);
    k
}

pub fn encode_key(k: &[u8; 32]) -> String {
    URL_SAFE_NO_PAD.encode(k)
}

pub fn decode_key(s: &str) -> Result<[u8; 32]> {
    let bytes = URL_SAFE_NO_PAD.decode(s.trim()).map_err(|_| Error::msg("bad key"))?;
    <[u8; 32]>::try_from(bytes.as_slice()).map_err(|_| Error::msg("bad key length"))
}

/// Random id for a paired device.
pub fn new_device_id() -> String {
    let mut b = [0u8; 12];
    rand::thread_rng().fill_bytes(&mut b);
    URL_SAFE_NO_PAD.encode(b)
}

/// base64(nonce || ciphertext+tag)
pub fn seal(key: &[u8; 32], plain: &[u8]) -> Result<String> {
    let cipher = Aes256Gcm::new(key.into());
    let nonce = Aes256Gcm::generate_nonce(&mut OsRng);
    let ct = cipher.encrypt(&nonce, plain).map_err(|_| Error::msg("encryption failed"))?;
    let mut out = nonce.to_vec();
    out.extend(ct);
    Ok(STANDARD.encode(out))
}

pub fn open(key: &[u8; 32], sealed: &str) -> Result<Vec<u8>> {
    let bytes = STANDARD.decode(sealed.trim()).map_err(|_| Error::msg("bad payload"))?;
    if bytes.len() < 12 + 16 {
        return Err(Error::msg("payload too short"));
    }
    let (nonce, ct) = bytes.split_at(12);
    Aes256Gcm::new(key.into())
        .decrypt(Nonce::from_slice(nonce), ct)
        .map_err(|_| Error::msg("wrong key or corrupted data"))
}
