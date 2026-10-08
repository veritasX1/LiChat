//! LiChat core: the end-to-end encryption every LiChat app shares (Android via uniffi, Ubuntu and lichat-cli via pyo3).
//!
//! MLS (RFC 9420) with OpenMLS. One [`Device`] holds its identity (signature key), a supply of key packages for others
//! to start conversations with it, and its conversations (MLS groups). Everything that leaves the device is opaque
//! bytes for the server's mailbox: welcomes, commits and application messages. The server never sees a plaintext, a
//! group name or who sent what (docs/ARCHITEKTUR.md).
//!
//! Step 1 keeps the state in memory; the encrypted store on the device follows (docs, „nächste Schritte“).

use std::collections::HashMap;

use openmls::prelude::{tls_codec::*, *};
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::OpenMlsRustCrypto;
use openmls_traits::OpenMlsProvider;

/// X25519, AES-128-GCM, SHA-256, Ed25519 – the MLS default, available on every device.
pub const CIPHERSUITE: Ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

#[derive(Debug, PartialEq, Eq)]
pub enum Error {
    /// Bytes that are no MLS message / key package / welcome of ours.
    Malformed(String),
    /// A key package whose signature or lifetime does not hold.
    InvalidKeyPackage(String),
    /// A conversation this device does not know (or was removed from).
    UnknownConversation,
    /// MLS refused (wrong epoch, wrong key, replay …).
    Mls(String),
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}

impl std::error::Error for Error {}

fn mls<E: std::fmt::Debug>(error: E) -> Error {
    Error::Mls(format!("{error:?}"))
}

/// What arrived from the mailbox, after decryption.
#[derive(Debug, PartialEq, Eq)]
pub enum Received {
    /// A text (or any payload) in a conversation, with the sender's identity as MLS proved it.
    Message { conversation: Vec<u8>, sender: String, payload: Vec<u8> },
    /// Someone changed the conversation's keys or members; nothing to show, the epoch moved on.
    Changed { conversation: Vec<u8> },
}

/// A new conversation: the bytes for the other devices' mailboxes.
pub struct Started {
    pub conversation: Vec<u8>,
    /// For the invited devices: they join with [`Device::join`].
    pub welcome: Vec<u8>,
}

pub struct Device {
    provider: OpenMlsRustCrypto,
    signer: SignatureKeyPair,
    credential: CredentialWithKey,
    identity: String,
    conversations: HashMap<Vec<u8>, MlsGroup>,
}

impl Device {
    /// A new device for the user [identity] (the LiChat user name): a fresh signature key, nothing else.
    pub fn new(identity: &str) -> Result<Self, Error> {
        let provider = OpenMlsRustCrypto::default();
        let signer = SignatureKeyPair::new(CIPHERSUITE.signature_algorithm()).map_err(mls)?;
        signer.store(provider.storage()).map_err(mls)?;
        let credential = CredentialWithKey {
            credential: BasicCredential::new(identity.as_bytes().to_vec()).into(),
            signature_key: signer.to_public_vec().into(),
        };
        Ok(Self { provider, signer, credential, identity: identity.to_string(), conversations: HashMap::new() })
    }

    pub fn identity(&self) -> &str {
        &self.identity
    }

    /// The public signature key – what the directory publishes and what the safety code is made from.
    pub fn identity_key(&self) -> Vec<u8> {
        self.signer.to_public_vec()
    }

    /// [count] key packages for the server's supply (each can start one conversation with this device).
    pub fn key_packages(&self, count: usize) -> Result<Vec<Vec<u8>>, Error> {
        (0..count)
            .map(|_| {
                let bundle = KeyPackage::builder()
                    .build(CIPHERSUITE, &self.provider, &self.signer, self.credential.clone())
                    .map_err(mls)?;
                bundle.key_package().tls_serialize_detached().map_err(mls)
            })
            .collect()
    }

    fn create_config() -> MlsGroupCreateConfig {
        MlsGroupCreateConfig::builder().ciphersuite(CIPHERSUITE).use_ratchet_tree_extension(true).build()
    }

    fn join_config() -> MlsGroupJoinConfig {
        MlsGroupJoinConfig::builder().use_ratchet_tree_extension(true).build()
    }

    /// Starts a conversation with the devices whose key packages were claimed from the server. Every key package is
    /// checked (signature, lifetime) first – a server cannot slip in a key of its own without failing here.
    pub fn start_conversation(&mut self, key_packages: &[Vec<u8>]) -> Result<Started, Error> {
        let mut checked = Vec::new();
        for bytes in key_packages {
            let incoming = KeyPackageIn::tls_deserialize_exact(bytes).map_err(|e| Error::Malformed(format!("{e:?}")))?;
            let package = incoming
                .validate(self.provider.crypto(), ProtocolVersion::Mls10)
                .map_err(|e| Error::InvalidKeyPackage(format!("{e:?}")))?;
            checked.push(package);
        }
        let mut group = MlsGroup::new(&self.provider, &self.signer, &Self::create_config(), self.credential.clone()).map_err(mls)?;
        let (_commit, welcome, _info) = group.add_members(&self.provider, &self.signer, &checked).map_err(mls)?;
        group.merge_pending_commit(&self.provider).map_err(mls)?;
        let conversation = group.group_id().as_slice().to_vec();
        let welcome = welcome.tls_serialize_detached().map_err(mls)?;
        self.conversations.insert(conversation.clone(), group);
        Ok(Started { conversation, welcome })
    }

    /// Joins the conversation a welcome invites this device to.
    pub fn join(&mut self, welcome: &[u8]) -> Result<Vec<u8>, Error> {
        let message = MlsMessageIn::tls_deserialize_exact(welcome).map_err(|e| Error::Malformed(format!("{e:?}")))?;
        let MlsMessageBodyIn::Welcome(welcome) = message.extract() else {
            return Err(Error::Malformed("kein Welcome".into()));
        };
        let group = StagedWelcome::new_from_welcome(&self.provider, &Self::join_config(), welcome, None)
            .map_err(mls)?
            .into_group(&self.provider)
            .map_err(mls)?;
        let conversation = group.group_id().as_slice().to_vec();
        self.conversations.insert(conversation.clone(), group);
        Ok(conversation)
    }

    /// Encrypts [payload] for everyone in the conversation.
    pub fn encrypt(&mut self, conversation: &[u8], payload: &[u8]) -> Result<Vec<u8>, Error> {
        let group = self.conversations.get_mut(conversation).ok_or(Error::UnknownConversation)?;
        group.create_message(&self.provider, &self.signer, payload).map_err(mls)?.tls_serialize_detached().map_err(mls)
    }

    /// New keys for this device in the conversation (forward secrecy / healing after a compromise). The returned commit
    /// goes to every other device of the conversation.
    pub fn update_keys(&mut self, conversation: &[u8]) -> Result<Vec<u8>, Error> {
        let group = self.conversations.get_mut(conversation).ok_or(Error::UnknownConversation)?;
        let bundle = group.self_update(&self.provider, &self.signer, Default::default()).map_err(mls)?;
        let commit = bundle.commit().tls_serialize_detached().map_err(mls)?;
        group.merge_pending_commit(&self.provider).map_err(mls)?;
        Ok(commit)
    }

    /// Handles one message from the mailbox (application message or commit of a conversation this device is in).
    pub fn receive(&mut self, bytes: &[u8]) -> Result<Received, Error> {
        let message = MlsMessageIn::tls_deserialize_exact(bytes).map_err(|e| Error::Malformed(format!("{e:?}")))?;
        let protocol = message.try_into_protocol_message().map_err(|e| Error::Malformed(format!("{e:?}")))?;
        let conversation = protocol.group_id().as_slice().to_vec();
        let group = self.conversations.get_mut(&conversation).ok_or(Error::UnknownConversation)?;
        let processed = group.process_message(&self.provider, protocol).map_err(mls)?;
        let sender = BasicCredential::try_from(processed.credential().clone())
            .map(|c| String::from_utf8_lossy(c.identity()).into_owned())
            .unwrap_or_default();
        match processed.into_content() {
            ProcessedMessageContent::ApplicationMessage(message) => {
                Ok(Received::Message { conversation, sender, payload: message.into_bytes() })
            }
            ProcessedMessageContent::StagedCommitMessage(staged) => {
                group.merge_staged_commit(&self.provider, *staged).map_err(mls)?;
                Ok(Received::Changed { conversation })
            }
            _ => Ok(Received::Changed { conversation }),
        }
    }

    /// The members' identities of a conversation (for the header „Anna, Bernd“).
    pub fn members(&self, conversation: &[u8]) -> Result<Vec<String>, Error> {
        let group = self.conversations.get(conversation).ok_or(Error::UnknownConversation)?;
        Ok(group
            .members()
            .filter_map(|m| BasicCredential::try_from(m.credential).ok())
            .map(|c| String::from_utf8_lossy(c.identity()).into_owned())
            .collect())
    }

    /// The epoch authenticator: the same on every device of the conversation – compared, it shows nobody sits in between.
    pub fn safety_code(&self, conversation: &[u8]) -> Result<Vec<u8>, Error> {
        let group = self.conversations.get(conversation).ok_or(Error::UnknownConversation)?;
        Ok(group.epoch_authenticator().as_slice().to_vec())
    }
}
