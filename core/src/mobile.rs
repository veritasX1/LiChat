//! The core for Android (Kotlin via uniffi): the same Device as for Python. One object, guarded by a mutex – the app
//! calls it from its service and its screens.

use std::sync::Mutex;

use crate::{Device as Core, Error, Received};

#[derive(Debug, uniffi::Error)]
pub enum CoreError {
    /// Bytes that are no MLS message / key package / welcome of ours, a broken state, a wrong key.
    Malformed { reason: String },
    InvalidKeyPackage { reason: String },
    UnknownConversation,
    /// MLS refused (wrong epoch, replay …).
    Refused { reason: String },
}

impl std::fmt::Display for CoreError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}

impl std::error::Error for CoreError {}

impl From<Error> for CoreError {
    fn from(error: Error) -> Self {
        match error {
            Error::Malformed(reason) => CoreError::Malformed { reason },
            Error::InvalidKeyPackage(reason) => CoreError::InvalidKeyPackage { reason },
            Error::UnknownConversation => CoreError::UnknownConversation,
            Error::Mls(reason) => CoreError::Refused { reason },
        }
    }
}

fn key(bytes: &[u8]) -> Result<[u8; 32], CoreError> {
    bytes.try_into().map_err(|_| CoreError::Malformed { reason: "Schlüssel muss 32 Bytes haben".into() })
}

#[derive(uniffi::Record)]
pub struct Started {
    pub conversation: Vec<u8>,
    pub welcome: Vec<u8>,
}

#[derive(uniffi::Record)]
pub struct Owner {
    pub identity: String,
    pub signature_key: Vec<u8>,
}

#[derive(uniffi::Enum)]
pub enum Incoming {
    Message { conversation: Vec<u8>, sender: String, payload: Vec<u8> },
    Changed { conversation: Vec<u8>, by: String },
    Removed { conversation: Vec<u8>, by: String },
}

#[derive(uniffi::Object)]
pub struct Device {
    inner: Mutex<Core>,
}

impl Device {
    fn with<T>(&self, f: impl FnOnce(&mut Core) -> Result<T, Error>) -> Result<T, CoreError> {
        let mut inner = self.inner.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        f(&mut inner).map_err(CoreError::from)
    }
}

#[uniffi::export]
impl Device {
    #[uniffi::constructor]
    pub fn new(identity: String) -> Result<Self, CoreError> {
        Ok(Self { inner: Mutex::new(Core::new(&identity)?) })
    }

    #[uniffi::constructor]
    pub fn load(sealed: Vec<u8>, key_bytes: Vec<u8>) -> Result<Self, CoreError> {
        Ok(Self { inner: Mutex::new(Core::load(&sealed, &key(&key_bytes)?)?) })
    }

    pub fn save(&self, key_bytes: Vec<u8>) -> Result<Vec<u8>, CoreError> {
        let key = key(&key_bytes)?;
        self.with(|d| d.save(&key))
    }

    pub fn identity(&self) -> String {
        self.inner.lock().unwrap_or_else(|p| p.into_inner()).identity().to_string()
    }

    pub fn identity_key(&self) -> Vec<u8> {
        self.inner.lock().unwrap_or_else(|p| p.into_inner()).identity_key()
    }

    pub fn key_packages(&self, count: u32) -> Result<Vec<Vec<u8>>, CoreError> {
        self.with(|d| d.key_packages(count as usize))
    }

    pub fn last_resort_key_package(&self) -> Result<Vec<u8>, CoreError> {
        self.with(|d| d.last_resort_key_package())
    }

    pub fn start_conversation(&self, key_packages: Vec<Vec<u8>>) -> Result<Started, CoreError> {
        self.with(|d| d.start_conversation(&key_packages)).map(|s| Started { conversation: s.conversation, welcome: s.welcome })
    }

    pub fn join(&self, welcome: Vec<u8>) -> Result<Vec<u8>, CoreError> {
        self.with(|d| d.join(&welcome))
    }

    pub fn encrypt(&self, conversation: Vec<u8>, payload: Vec<u8>) -> Result<Vec<u8>, CoreError> {
        self.with(|d| d.encrypt(&conversation, &payload))
    }

    pub fn update_keys(&self, conversation: Vec<u8>) -> Result<Vec<u8>, CoreError> {
        self.with(|d| d.update_keys(&conversation))
    }

    pub fn receive(&self, data: Vec<u8>) -> Result<Incoming, CoreError> {
        self.with(|d| d.receive(&data)).map(|r| match r {
            Received::Message { conversation, sender, payload } => Incoming::Message { conversation, sender, payload },
            Received::Changed { conversation, by } => Incoming::Changed { conversation, by },
            Received::Removed { conversation, by } => Incoming::Removed { conversation, by },
        })
    }

    pub fn remove_members(&self, conversation: Vec<u8>, identities: Vec<String>) -> Result<Vec<u8>, CoreError> {
        self.with(|d| d.remove_members(&conversation, &identities))
    }

    pub fn members(&self, conversation: Vec<u8>) -> Result<Vec<String>, CoreError> {
        self.with(|d| d.members(&conversation))
    }

    pub fn conversations(&self) -> Vec<Vec<u8>> {
        self.inner.lock().unwrap_or_else(|p| p.into_inner()).conversations()
    }

    pub fn safety_code(&self, conversation: Vec<u8>) -> Result<Vec<u8>, CoreError> {
        self.with(|d| d.safety_code(&conversation))
    }
}

/// Whose a claimed key package is (signature checked) – compared with the directory before a conversation starts.
#[uniffi::export]
pub fn key_package_owner(key_package: Vec<u8>) -> Result<Owner, CoreError> {
    let (identity, signature_key) = Core::key_package_owner(&key_package)?;
    Ok(Owner { identity, signature_key })
}
