package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/exec"
	"testing"
	"time"
)

func TestDiscoveryParsing(t *testing.T) {
	lan := &net.IPNet{IP: net.IPv4(192, 168, 1, 2), Mask: net.CIDRMask(24, 32)}
	src := net.IPv4(192, 168, 1, 3)
	for _, bad := range []string{"", "NP1 test private", "NP-HOST/1 0 x", "NP-HOST/1 65536 x", "NP-HOST/1 123 x\nx", "NP-HOST/1 01 x", "NP-HOST/1 123  "} {
		if _, ok := parseBeacon([]byte(bad), src, lan); ok {
			t.Fatal(bad)
		}
	}
	b := beacon(5555, "Laptop")
	for _, invalid := range []net.IP{lan.IP, net.IPv4(192, 168, 2, 3)} {
		if _, ok := parseBeacon(b, invalid, lan); ok {
			t.Fatal("unexpected source")
		}
	}
	if p, ok := parseBeacon(b, src, lan); !ok || p.name != "Laptop" || p.endpoint.Port != 5555 {
		t.Fatal("valid beacon missing")
	}
}
func TestSelectedConnection(t *testing.T) {
	l, v := fixture(t, net.IPv4(127, 0, 0, 2))
	ctx, stop := context.WithTimeout(context.Background(), 4*time.Second)
	defer stop()
	done := make(chan error, 1)
	go func() {
		done <- serve(ctx, l, v, func(context.Context, string, string) bool { return true }, func(string) error { return nil }, func(string) {})
	}()
	endpoint := l.Addr().(*net.TCPAddr)
	wrong := *endpoint
	wrong.Port++
	if _, _, err := connectHost(v.key, &wrong, func(string) {}); err == nil {
		t.Fatal("wrong host connected")
	}
	c, r, e := connectHost(v.key, endpoint, func(string) {})
	if e != nil {
		t.Fatal(e)
	}
	defer c.Close()
	fmt.Fprintln(c, "DOWN")
	if s, _ := readLine(r); s != "OK" {
		t.Fatal(s)
	}
	fmt.Fprintln(c, "STOP")
	if e := <-done; e != nil {
		t.Fatal(e)
	}
}
func TestJavaAdvertisesToGo(t *testing.T) {
	cp := os.Getenv("NODEPORT_TEST_CLASSES")
	if cp == "" {
		t.Skip("JVM classes not configured")
	}
	ctx, stop := context.WithTimeout(context.Background(), 6*time.Second)
	defer stop()
	sock, e := net.ListenUDP("udp4", &net.UDPAddr{Port: discoveryPort})
	if e != nil {
		t.Fatal(e)
	}
	defer sock.Close()
	cmd := exec.CommandContext(ctx, "java", "-cp", cp, "io.nodeport.link.ProtocolSmoke", "announce")
	if e = cmd.Start(); e != nil {
		t.Fatal(e)
	}
	defer func() { stop(); cmd.Wait() }()
	lan := &net.IPNet{IP: net.IPv4(127, 0, 0, 1), Mask: net.CIDRMask(8, 32)}
	ps, e := collect(sock, lan, 2*time.Second)
	if e != nil || len(ps) != 1 || ps[0].name != "Android fixture" || ps[0].endpoint.Port != 4321 {
		t.Fatal(ps, e)
	}
}

func TestEmptyScanHasNoFixtures(t *testing.T) {
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	ps, err := collect(c, &net.IPNet{IP: net.IPv4(127, 0, 0, 1), Mask: net.CIDRMask(8, 32)}, 30*time.Millisecond)
	if err != nil || len(ps) != 0 {
		t.Fatalf("unexpected discovery without beacons: %v %v", ps, err)
	}
}
