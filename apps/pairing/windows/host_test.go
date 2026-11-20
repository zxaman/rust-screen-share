package main

import (
	"bufio"
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func fixture(t *testing.T, ip net.IP) (net.Listener, invite) {
	t.Helper()
	l, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: ip})
	if err != nil {
		t.Fatal(err)
	}
	v, err := newInvite(ip, l.Addr().(*net.TCPAddr).Port)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	return l, v
}
func dial(t *testing.T, addr string, v invite) *tls.Conn {
	t.Helper()
	key, _ := b64.DecodeString(v.key[4:])
	c, e := tls.DialWithDialer(&net.Dialer{Timeout: time.Second}, "tcp4", addr, &tls.Config{MinVersion: tls.VersionTLS12, InsecureSkipVerify: true, VerifyConnection: func(s tls.ConnectionState) error {
		h := sha256.Sum256(s.PeerCertificates[0].Raw)
		if subtle.ConstantTimeCompare(h[:16], key[6:22]) != 1 {
			return errors.New("pin mismatch")
		}
		return nil
	}})
	if e != nil {
		t.Fatal(e)
	}
	c.SetDeadline(time.Now().Add(3 * time.Second))
	t.Cleanup(func() { c.Close() })
	return c
}
func TestConsentAndRevocation(t *testing.T) {
	for _, allow := range []bool{false, true} {
		t.Run(fmt.Sprint(allow), func(t *testing.T) {
			l, v := fixture(t, net.IPv4(127, 0, 0, 1))
			ctx, stop := context.WithCancel(context.Background())
			defer stop()
			pending := make(chan string, 1)
			decision := make(chan bool, 1)
			var count atomic.Int32
			done := make(chan error, 1)
			go func() {
				done <- serve(ctx, l, v, func(p context.Context, peer, code string) bool {
					pending <- code
					select {
					case a := <-decision:
						return a
					case <-p.Done():
						return false
					}
				}, func(command string) error {
					if command != "UP" {
						return errors.New("unexpected command")
					}
					count.Add(1)
					return nil
				}, func(string) {})
			}()
			bad := dial(t, l.Addr().String(), v)
			fmt.Fprintln(bad, "NP1 invalid 000000")
			if reply, e := readLine(bufio.NewReader(bad)); e != nil || reply != "BAD_INVITE" {
				t.Fatal("invalid key was not rejected")
			}
			bad.Close()
			c := dial(t, l.Addr().String(), v)
			fmt.Fprintln(c, "NP1", v.secret, "123456")
			reader := bufio.NewReader(c)
			select {
			case code := <-pending:
				if code != "123456" {
					t.Fatal("incorrect prompt")
				}
			case <-time.After(time.Second):
				t.Fatal("missing prompt")
			}
			if count.Load() != 0 {
				t.Fatal("access before approval")
			}
			if replay, e := net.DialTimeout("tcp4", l.Addr().String(), time.Second); e == nil {
				replay.Close()
				t.Fatal("invite still listening")
			}
			decision <- allow
			s, e := readLine(reader)
			if e != nil {
				t.Fatal(e)
			}
			if allow {
				if s != "APPROVED VOLUME" {
					t.Fatal(s)
				}
				fmt.Fprintln(c, "UP")
				s, _ = readLine(reader)
				if s != "OK" || count.Load() != 1 {
					t.Fatal("no approved action")
				}
				stop()
			} else if s != "DENIED" {
				t.Fatal(s)
			}
			select {
			case <-done:
			case <-time.After(2 * time.Second):
				t.Fatal("host failed to stop")
			}
			if _, e = readLine(reader); e == nil {
				t.Fatal("session still usable")
			}
		})
	}
}
func TestJavaController(t *testing.T) {
	cp := os.Getenv("NODEPORT_TEST_CLASSES")
	if cp == "" {
		t.Skip("set NODEPORT_TEST_CLASSES for cross-runtime test")
	}
	ip := net.IPv4(127, 0, 0, 2)
	l, v := fixture(t, ip)
	ctx, stop := context.WithTimeout(context.Background(), 10*time.Second)
	defer stop()
	done := make(chan error, 1)
	var n atomic.Int32
	go func() {
		done <- serve(ctx, l, v, func(_ context.Context, _ string, c string) bool { return isCode(c) }, func(s string) error {
			if s == "UP" {
				n.Add(1)
			}
			return nil
		}, func(string) {})
	}()
	go advertise(ctx, &net.IPNet{IP: ip, Mask: net.CIDRMask(8, 32)}, l.Addr().(*net.TCPAddr).Port, "PC fixture")
	cmd := exec.CommandContext(ctx, "java", "-cp", cp, "io.nodeport.link.ProtocolSmoke")
	cmd.Stdin = strings.NewReader(v.key + "\n")
	if out, e := cmd.CombinedOutput(); e != nil {
		t.Fatalf("Java client: %v %s", e, out)
	}
	if e := <-done; e != nil || n.Load() != 1 {
		t.Fatal(e, n.Load())
	}
}
