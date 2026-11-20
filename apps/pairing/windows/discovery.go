package main

// Discovery metadata is public and unauthenticated. It must never carry a key
// or be used to authorize a session. The IP always comes from the UDP source.
import (
	"context"
	"fmt"
	"net"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const discoveryPort = 48173
const beaconVersion = "NP-HOST/1"

var validLabel = regexp.MustCompile(`^[a-zA-Z0-9._ -]{1,48}$`)

type peer struct {
	name     string
	endpoint *net.TCPAddr
}

func beacon(port int, name string) []byte {
	name = regexp.MustCompile(`[^a-zA-Z0-9._ -]`).ReplaceAllString(name, "_")
	if len(name) > 48 {
		name = name[:48]
	}
	if strings.TrimSpace(name) == "" {
		name = "Nodeport"
	}
	return []byte(fmt.Sprintf("%s %d %s", beaconVersion, port, name))
}
func parseBeacon(msg []byte, source net.IP, lan *net.IPNet) (peer, bool) {
	fields := strings.SplitN(string(msg), " ", 3)
	if len(msg) > 80 || len(fields) != 3 || fields[0] != beaconVersion || !validLabel.MatchString(fields[2]) || strings.TrimSpace(fields[2]) == "" || source.To4() == nil || !lan.Contains(source) || source.Equal(lan.IP) {
		return peer{}, false
	}
	port, err := strconv.Atoi(fields[1])
	if err != nil || port < 1 || port > 65535 || strconv.Itoa(port) != fields[1] {
		return peer{}, false
	}
	return peer{fields[2], &net.TCPAddr{IP: source, Port: port}}, true
}
func broadcast(lan *net.IPNet) net.IP {
	ip := append(net.IP(nil), lan.IP.To4()...)
	for i := range ip {
		ip[i] |= ^lan.Mask[i]
	}
	return ip
}

// The same bounded, non-secret beacon on a second, link-local transport.
// On systems without multicast support direct broadcast remains usable.
var group = net.IPv4(239, 255, 43, 73)

func configureGroup(c *net.UDPConn, ip net.IP, receive bool) error {
	rc, err := c.SyscallConn()
	if err != nil {
		return err
	}
	var optionErr error
	err = rc.Control(func(fd uintptr) {
		h := socketHandle(fd)
		addr := [4]byte(ip.To4())
		if receive {
			m := syscall.IPMreq{Multiaddr: [4]byte(group.To4()), Interface: addr}
			optionErr = syscall.SetsockoptIPMreq(h, syscall.IPPROTO_IP, syscall.IP_ADD_MEMBERSHIP, &m)
		} else {
			optionErr = syscall.SetsockoptInet4Addr(h, syscall.IPPROTO_IP, syscall.IP_MULTICAST_IF, addr)
			if optionErr == nil {
				optionErr = syscall.SetsockoptInt(h, syscall.IPPROTO_IP, syscall.IP_MULTICAST_TTL, 1)
			}
		}
	})
	if err != nil {
		return err
	}
	return optionErr
}
func advertise(ctx context.Context, lan *net.IPNet, port int, name string) error {
	c, e := net.ListenUDP("udp4", &net.UDPAddr{IP: lan.IP})
	if e != nil {
		return e
	}
	defer c.Close()
	payload := beacon(port, name)
	multicast := configureGroup(c, lan.IP, false) == nil
	bc := &net.UDPAddr{IP: broadcast(lan), Port: discoveryPort}
	mc := &net.UDPAddr{IP: group, Port: discoveryPort}
	ticker := time.NewTicker(750 * time.Millisecond)
	defer ticker.Stop()
	for ctx.Err() == nil {
		c.SetWriteDeadline(time.Now().Add(time.Second))
		_, e = c.WriteToUDP(payload, bc)
		if multicast {
			_, me := c.WriteToUDP(payload, mc)
			if me == nil {
				e = nil
			}
		}
		if e != nil {
			return e
		}
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
		}
	}
	return nil
}
func collect(c *net.UDPConn, lan *net.IPNet, duration time.Duration) ([]peer, error) {
	seen := map[string]peer{}
	end := time.Now().Add(duration)
	c.SetReadDeadline(end)
	buf := make([]byte, 81)
	for len(seen) < 32 && time.Now().Before(end) {
		n, src, e := c.ReadFromUDP(buf)
		if e != nil {
			if ne, ok := e.(net.Error); ok && ne.Timeout() {
				break
			}
			return nil, e
		}
		if p, ok := parseBeacon(buf[:n], src.IP, lan); ok {
			seen[p.endpoint.String()] = p
		}
	}
	out := make([]peer, 0, len(seen))
	for _, p := range seen {
		out = append(out, p)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].endpoint.String() < out[j].endpoint.String() })
	return out, nil
}
func scan(lan *net.IPNet) ([]peer, error) {
	c, e := net.ListenUDP("udp4", &net.UDPAddr{Port: discoveryPort})
	if e != nil {
		return nil, e
	}
	defer c.Close()
	_ = configureGroup(c, lan.IP, true)
	return collect(c, lan, 6*time.Second)
}
