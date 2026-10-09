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
    assert_eq!(anna.receive(&commit).unwrap(), Received::Changed { conversation: joined.clone(), by: "bernd".into() });
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

/// The state survives a restart only sealed: saved, loaded with the key, the conversation goes on; wrong key refused.
#[test]
fn save_and_load() {
    let key = [7u8; 32];
    let mut anna = Device::new("anna").unwrap();
    let mut bernd = Device::new("bernd").unwrap();
    let spare = bernd.key_packages(2).unwrap();
    let started = anna.start_conversation(&spare[..1]).unwrap();
    bernd.join(&started.welcome).unwrap();
    let id = started.conversation;

    let sealed = bernd.save(&key).unwrap();
    assert!(!sealed.windows(5).any(|w| w == b"bernd"), "Identität im Klartext");
    assert!(Device::load(&sealed, &[8u8; 32]).is_err());
    let mut bernd = Device::load(&sealed, &key).unwrap();
    assert_eq!(bernd.identity(), "bernd");
    assert_eq!(bernd.conversations(), vec![id.clone()]);

    let hello = anna.encrypt(&id, b"nach dem Neustart").unwrap();
    assert!(matches!(bernd.receive(&hello).unwrap(), Received::Message { payload, .. } if payload == b"nach dem Neustart"));
    let back = bernd.encrypt(&id, b"bin wieder da").unwrap();
    assert!(matches!(anna.receive(&back).unwrap(), Received::Message { sender, .. } if sender == "bernd"));

    // The unused key package from before the restart still works (its private key came along).
    let mut erna = Device::new("erna").unwrap();
    let late = erna.start_conversation(&spare[1..]).unwrap();
    assert!(bernd.join(&late.welcome).is_ok());
}

#[test]
fn last_resort_and_owner() {
    let mut anna = Device::new("anna").unwrap();
    let mut erna = Device::new("erna").unwrap();
    let bernd = Device::new("bernd").unwrap();

    // Who a key package belongs to – the app checks it against the directory.
    let reserve = bernd.last_resort_key_package().unwrap();
    assert_eq!(Device::key_package_owner(&reserve).unwrap(), ("bernd".to_string(), bernd.identity_key()));
    let mut forged = reserve.clone();
    let last = forged.len() - 1;
    forged[last] ^= 1;
    assert!(Device::key_package_owner(&forged).is_err());

    // The reserve can start more than one conversation (an ordinary key package only one).
    let mut bernd = bernd;
    let first = anna.start_conversation(&[reserve.clone()]).unwrap();
    assert_eq!(bernd.join(&first.welcome).unwrap(), first.conversation);
    let second = erna.start_conversation(&[reserve]).unwrap();
    assert_eq!(bernd.join(&second.welcome).unwrap(), second.conversation);

    let once = bernd.key_packages(1).unwrap();
    let a = anna.start_conversation(&once).unwrap();
    bernd.join(&a.welcome).unwrap();
    let b = erna.start_conversation(&once).unwrap();
    assert!(bernd.join(&b.welcome).is_err(), "gewöhnliches Key-Package zweimal benutzt");
}

#[test]
fn remove_a_member() {
    let mut anna = Device::new("anna").unwrap();
    let mut bernd = Device::new("bernd").unwrap();
    let mut erna = Device::new("erna").unwrap();
    let mut supply = bernd.key_packages(1).unwrap();
    supply.extend(erna.key_packages(1).unwrap());
    let started = anna.start_conversation(&supply).unwrap();
    let cid = started.conversation.clone();
    bernd.join(&started.welcome).unwrap();
    erna.join(&started.welcome).unwrap();

    let commit = anna.remove_members(&cid, &["erna".to_string()]).unwrap();
    assert_eq!(anna.members(&cid).unwrap(), vec!["anna", "bernd"]);
    assert_eq!(bernd.receive(&commit).unwrap(), Received::Changed { conversation: cid.clone(), by: "anna".into() });
    assert_eq!(bernd.members(&cid).unwrap(), vec!["anna", "bernd"]);
    assert_eq!(erna.receive(&commit).unwrap(), Received::Removed { conversation: cid.clone(), by: "anna".into() });
    assert!(erna.conversations().is_empty());

    // What follows is for Anna and Bernd only – Erna cannot read it any more.
    let after = anna.encrypt(&cid, b"ohne Erna").unwrap();
    assert!(matches!(bernd.receive(&after).unwrap(), Received::Message { payload, .. } if payload == b"ohne Erna"));
    assert!(erna.receive(&after).is_err());
    assert!(anna.remove_members(&cid, &["niemand".to_string()]).is_err());
}
