//! Local policy primitives. Transport authentication and OS consent are adapter responsibilities.
use std::collections::BTreeSet;
use std::time::Instant;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Capability {
    ViewScreen,
    Input,
    Files,
    Information,
    Terminal,
    Hardware,
    Administration,
}

/// Created by an owner-consent adapter after authenticating the peer. Grants never imply one another.
pub struct Session {
    peer: String,
    grants: BTreeSet<Capability>,
    deadline: Instant,
    revoked: bool,
}
impl Session {
    pub fn approved(
        peer: String,
        grants: impl IntoIterator<Item = Capability>,
        deadline: Instant,
    ) -> Self {
        Self {
            peer,
            grants: grants.into_iter().collect(),
            deadline,
            revoked: false,
        }
    }
    pub fn allows(&self, peer: &str, capability: Capability, now: Instant) -> bool {
        !self.revoked
            && now < self.deadline
            && self.peer == peer
            && self.grants.contains(&capability)
    }
    pub fn revoke(&mut self) {
        self.revoked = true;
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn grants_are_scoped_and_terminable() {
        let now = Instant::now();
        let end = now + std::time::Duration::from_secs(60);
        let mut s = Session::approved("workstation".into(), [Capability::ViewScreen], end);
        assert!(s.allows("workstation", Capability::ViewScreen, now));
        assert!(!s.allows("workstation", Capability::Input, now));
        assert!(!s.allows("other", Capability::ViewScreen, now));
        assert!(!s.allows("workstation", Capability::ViewScreen, end));
        s.revoke();
        assert!(!s.allows("workstation", Capability::ViewScreen, now));
    }
}

/// In-memory owner-approved peers; sessions do not survive process restarts.
/// A host must supply verified peer identities, local time and consent events.
#[derive(Default)]
pub struct Workspace {
    paired: BTreeSet<String>,
    sessions: std::collections::BTreeMap<String, Session>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum ApprovalError {
    UnpairedPeer,
    EmptyGrant,
    Expired,
}

impl Workspace {
    pub fn pair(&mut self, peer: String) {
        self.paired.insert(peer);
    }

    pub fn approve(
        &mut self,
        peer: &str,
        grants: &[Capability],
        now: Instant,
        deadline: Instant,
    ) -> Result<(), ApprovalError> {
        if !self.paired.contains(peer) {
            return Err(ApprovalError::UnpairedPeer);
        }
        if grants.is_empty() {
            return Err(ApprovalError::EmptyGrant);
        }
        if deadline <= now {
            return Err(ApprovalError::Expired);
        }
        self.sessions.insert(
            peer.into(),
            Session::approved(peer.into(), grants.iter().copied(), deadline),
        );
        Ok(())
    }

    pub fn allows(&self, peer: &str, capability: Capability, now: Instant) -> bool {
        self.sessions
            .get(peer)
            .is_some_and(|session| session.allows(peer, capability, now))
    }

    pub fn end(&mut self, peer: &str) {
        self.sessions.remove(peer);
    }

    pub fn unpair(&mut self, peer: &str) {
        self.end(peer);
        self.paired.remove(peer);
    }
}
