//! Step 1 (README): two devices, a conversation, messages both ways, a key change – and what must fail.

use lichat_core::{Device, Error, Received};

#[test]
fn messages_both_ways_and_key_change() {
    let mut anna = Device::new("anna").unwrap();
    let mut bernd = Device::new("bernd").unwrap();

    // Bernd's supply on the server; Anna claims one and starts the conversation.
    let supply = bernd.key_packages(3).unwrap();
    assert_eq!(supply.len(), 3);
    let started = anna.start_conversation(&supply[..1]).unwrap();
    let joined = bernd.join(&started.welcome).unwrap();
    assert_eq!(joined, started.conversation);
    assert_eq!(anna.members(&joined).unwrap(), vec!["anna", "bernd"]);
    assert_eq!(anna.safety_code(&joined).unwrap(), bernd.safety_code(&joined).unwrap());

    let hello = anna.encrypt(&joined, "Hallo Bernd!".as_bytes()).unwrap();
    assert!(!hello.windows(5).any(|w| w == b"Hallo"), "Klartext im Paket");
    assert_eq!(bernd.receive(&hello).unwrap(),
               Received::Message { conversation: joined.clone(), sender: "anna".into(), payload: "Hallo Bernd!".into() });

    let reply = bernd.encrypt(&joined, b"Hallo Anna").unwrap();
    assert!(matches!(anna.receive(&reply).unwrap(), Received::Message { sender, .. } if sender == "bernd"));

    // Bernd renews his keys; Anna follows; both still talk and agree on the safety code.
    let before = anna.safety_code(&joined).unwrap();
    let commit = bernd.update_keys(&joined).unwrap();
    assert_eq!(anna.receive(&commit).unwrap(), Received::Changed { conversation: joined.clone() });
    assert_ne!(anna.safety_code(&joined).unwrap(), before);
    assert_eq!(anna.safety_code(&joined).unwrap(), bernd.safety_code(&joined).unwrap());
    let after = anna.encrypt(&joined, b"Neue Schluessel").unwrap();
    assert!(matches!(bernd.receive(&after).unwrap(), Received::Message { payload, .. } if payload == b"Neue Schluessel"));
}

#[test]
fn what_must_fail() {
    let mut anna = Device::new("anna").unwrap();
    let mut bernd = Device::new("bernd").unwrap();
    let mut erna = Device::new("erna").unwrap();
    let started = anna.start_conversation(&bernd.key_packages(1).unwrap()).unwrap();
    bernd.join(&started.welcome).unwrap();
    let message = anna.encrypt(&started.conversation, b"nur fuer Bernd").unwrap();

    // Erna is not in the conversation: nothing to read.
    assert_eq!(erna.receive(&message), Err(Error::UnknownConversation));
    // A welcome for Bernd is no use to Erna.
    assert!(erna.join(&started.welcome).is_err());
    // The same message twice (replay): refused the second time.
    assert!(bernd.receive(&message).is_ok());
    assert!(bernd.receive(&message).is_err());
    // A changed byte: refused (MLS then drops that message's key – a server could as well drop the message itself).
    let mut broken = anna.encrypt(&started.conversation, b"zweite Nachricht").unwrap();
    let last = broken.len() - 1;
    broken[last] ^= 1;
    assert!(bernd.receive(&broken).is_err());
    // The conversation goes on after it.
    let third = anna.encrypt(&started.conversation, b"dritte Nachricht").unwrap();
    assert!(matches!(bernd.receive(&third), Ok(Received::Message { payload, .. }) if payload == b"dritte Nachricht"));
    // A key package with a forged signature (a server swapping in its own key): refused before anything is sent.
    let mut forged = erna.key_packages(1).unwrap().remove(0);
    let at = forged.len() - 10;
    forged[at] ^= 1;
    let result = anna.start_conversation(&[forged]);
    assert!(matches!(result, Err(Error::InvalidKeyPackage(_)) | Err(Error::Malformed(_))), "{:?}", result.as_ref().map(|s| s.conversation.len()));
}

/// Olaf 08.10. (B): groups already in step 1 – one conversation, three people, everyone reads everyone.
#[test]
fn group_of_three() {
    let mut anna = Device::new("anna").unwrap();
    let mut bernd = Device::new("bernd").unwrap();
    let mut erna = Device::new("erna").unwrap();
    let packages = [bernd.key_packages(1).unwrap().remove(0), erna.key_packages(1).unwrap().remove(0)];
    let started = anna.start_conversation(&packages).unwrap();
    bernd.join(&started.welcome).unwrap();
    erna.join(&started.welcome).unwrap();
    let id = started.conversation;
    assert_eq!(erna.members(&id).unwrap(), vec!["anna", "bernd", "erna"]);
    let from_erna = erna.encrypt(&id, b"Kuchen um drei?").unwrap();
    for reader in [&mut anna, &mut bernd] {
        assert!(matches!(reader.receive(&from_erna).unwrap(), Received::Message { sender, .. } if sender == "erna"));
    }
    // Bernd renews his keys: both others follow, the group still talks.
    let commit = bernd.update_keys(&id).unwrap();
    anna.receive(&commit).unwrap();
    erna.receive(&commit).unwrap();
    assert_eq!(anna.safety_code(&id).unwrap(), erna.safety_code(&id).unwrap());
    let from_anna = anna.encrypt(&id, b"Ja!").unwrap();
    assert!(matches!(erna.receive(&from_anna).unwrap(), Received::Message { payload, .. } if payload == b"Ja!"));
}
