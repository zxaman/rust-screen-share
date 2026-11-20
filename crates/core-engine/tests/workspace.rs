use std::time::{Duration, Instant};
use uda_core_engine::{ApprovalError, Capability, Workspace};

#[test]
fn pairing_does_not_grant_access_and_unpairing_invalidates_previous_grants() {
    let mut ws = Workspace::default();
    let now = Instant::now();
    let end = now + Duration::from_secs(300);
    let view = Capability::ViewScreen;
    assert_eq!(
        ws.approve("laptop", &[view], now, end),
        Err(ApprovalError::UnpairedPeer)
    );
    ws.pair("laptop".into());
    assert!(!ws.allows("laptop", view, now));
    assert_eq!(
        ws.approve("laptop", &[], now, end),
        Err(ApprovalError::EmptyGrant)
    );
    assert_eq!(
        ws.approve("laptop", &[view], now, now),
        Err(ApprovalError::Expired)
    );
    ws.approve("laptop", &[view], now, end).unwrap();
    assert!(ws.allows("laptop", view, now));
    ws.unpair("laptop");
    ws.pair("laptop".into());
    assert!(!ws.allows("laptop", view, now));
}

#[test]
fn reapproval_replaces_instead_of_accumulating_privileges() {
    let mut ws = Workspace::default();
    let now = Instant::now();
    let end = now + Duration::from_secs(60);
    ws.pair("phone".into());
    ws.approve(
        "phone",
        &[Capability::Information, Capability::Files],
        now,
        end,
    )
    .unwrap();
    ws.approve("phone", &[Capability::Information], now, end)
        .unwrap();
    assert!(!ws.allows("phone", Capability::Files, now));
    ws.end("phone");
    assert!(!ws.allows("phone", Capability::Information, now));
}
