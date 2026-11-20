//! Pure route selection. Candidates must be measured, authenticated channels to
//! the same peer, supplied by the transport adapter, not discovery advertisements.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Link {
    BluetoothLe,
    LocalWifi,
    Ethernet,
    Internet,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Traffic {
    Control,
    Screen,
    BulkFile,
}

#[derive(Clone, Copy, Debug)]
pub struct Candidate {
    pub link: Link,
    pub available_kbps: u32,
    pub round_trip_ms: u32,
}

/// Prefer local routes meeting the caller's bandwidth requirement, then latency.
/// Re-evaluate after a network change; absence of an eligible route pauses traffic.
pub fn select(
    routes: &[Candidate],
    traffic: Traffic,
    min_kbps: u32,
    allow_internet: bool,
) -> Option<&Candidate> {
    routes
        .iter()
        .filter(|r| r.available_kbps > 0 && r.available_kbps >= min_kbps)
        .filter(|r| allow_internet || r.link != Link::Internet)
        .filter(|r| traffic == Traffic::Control || r.link != Link::BluetoothLe)
        .min_by_key(|r| {
            (
                r.link == Link::Internet,
                r.round_trip_ms,
                std::cmp::Reverse(r.available_kbps),
            )
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    const BLE: Candidate = Candidate {
        link: Link::BluetoothLe,
        available_kbps: 200,
        round_trip_ms: 4,
    };
    const LAN: Candidate = Candidate {
        link: Link::LocalWifi,
        available_kbps: 5000,
        round_trip_ms: 8,
    };
    const WAN: Candidate = Candidate {
        link: Link::Internet,
        available_kbps: 20000,
        round_trip_ms: 6,
    };
    #[test]
    fn never_stream_or_transfer_on_ble() {
        for traffic in [Traffic::Screen, Traffic::BulkFile] {
            assert!(select(&[BLE], traffic, 1, false).is_none());
        }
        assert!(select(&[BLE], Traffic::Control, 10, false).is_some());
    }
    #[test]
    fn prefer_local_and_respect_internet_opt_in() {
        let routes = [BLE, WAN, LAN];
        assert_eq!(
            select(&routes, Traffic::Screen, 1000, true).unwrap().link,
            Link::LocalWifi
        );
        assert!(select(&routes, Traffic::Screen, 9000, false).is_none());
        assert_eq!(
            select(&routes, Traffic::Screen, 9000, true).unwrap().link,
            Link::Internet
        );
        assert!(select(&routes, Traffic::Screen, 21000, true).is_none());
    }
}
