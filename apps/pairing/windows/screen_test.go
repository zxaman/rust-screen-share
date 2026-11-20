package main

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"image"
	"image/jpeg"
	"io"
	"net"
	qr "nodeport.local/pairing/internal/qrcode"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestScreenScopes(t *testing.T) {
	for _, tc := range []struct {
		scope, cmd string
		allow      bool
	}{{"SCREEN", "FRAME", false}, {"SCREEN", "CLICK 1 2", true}, {"SCREEN_CLICK", "CLICK 1 2", true}, {"SCREEN_CLICK", "UP", true}, {"VOLUME", "FRAME", true}} {
		t.Run(tc.scope+tc.cmd+fmt.Sprint(tc.allow), func(t *testing.T) {
			l, v := fixture(t, net.IPv4(127, 0, 0, 1))
			ctx, stop := context.WithTimeout(context.Background(), 3*time.Second)
			defer stop()
			done := make(chan error, 1)
			var captures, clicks, prompts atomic.Int32
			s := &screenAdapter{approve: func(p context.Context, peer, code, scope string) bool {
				prompts.Add(1)
				return scope == tc.scope && tc.allow
			}, started: func(string, string) {}, capture: func() ([]byte, error) { captures.Add(1); return []byte{0}, nil }, click: func(int, int) error { clicks.Add(1); return nil }}
			go func() {
				done <- serveWithScreen(ctx, l, v, func(context.Context, string, string) bool { return true }, func(string) error { return nil }, func(string) {}, s)
			}()
			c := dial(t, l.Addr().String(), v)
			if tc.scope == "VOLUME" {
				fmt.Fprintln(c, "NP1", v.secret, "123456")
			} else {
				fmt.Fprintln(c, "NP2", v.secret, "123456", tc.scope)
			}
			r := bufio.NewReader(c)
			answer, e := readLine(r)
			if e != nil {
				t.Fatal(e)
			}
			if tc.allow {
				if answer != "APPROVED "+tc.scope {
					t.Fatal(answer)
				}
				fmt.Fprintln(c, tc.cmd)
			} else if answer != "DENIED" {
				t.Fatal(answer)
			}
			if <-done == nil {
				t.Fatal("unauthorized action not rejected")
			}
			if clicks.Load() != 0 || captures.Load() != 0 {
				t.Fatal("adapter invoked")
			}
			if tc.scope != "VOLUME" && prompts.Load() != 1 {
				t.Fatal("missing screen prompt")
			}
		})
	}
}
func TestScreenJavaAndQR(t *testing.T) {
	cp := os.Getenv("NODEPORT_TEST_CLASSES")
	if cp == "" {
		t.Skip("JVM classes not configured")
	}
	ctx, stop := context.WithTimeout(context.Background(), 10*time.Second)
	defer stop()
	l, v := fixture(t, net.IPv4(127, 0, 0, 2))
	run := func(args ...string) {
		cmd := exec.CommandContext(ctx, "java", append([]string{"-cp", cp, "io.nodeport.link.ScreenSmoke"}, args...)...)
		cmd.Stdin = strings.NewReader(v.key + "\n")
		if b, e := cmd.CombinedOutput(); e != nil {
			t.Fatalf("%v %s", e, b)
		}
	}
	path := filepath.Join(t.TempDir(), "test-qr.png")
	if e := qr.WriteFile(v.key, qr.Medium, 400, path); e != nil {
		t.Fatal(e)
	}
	run("qr", path)
	var count, clicks atomic.Int32
	done := make(chan error, 1)
	var jpg bytes.Buffer
	jpeg.Encode(&jpg, image.NewRGBA(image.Rect(0, 0, 16, 16)), nil)
	adapter := &screenAdapter{approve: func(_ context.Context, peer, code, scope string) bool { return scope == "SCREEN_CLICK" }, started: func(string, string) {}, capture: func() ([]byte, error) { count.Add(1); return jpg.Bytes(), nil }, click: func(x, y int) error {
		if x != 1200 || y != 2300 {
			return errors.New("wrong click")
		}
		clicks.Add(1)
		return nil
	}}
	go func() { done <- serveWithScreen(ctx, l, v, nil, nil, nil, adapter) }()
	run("screen")
	if e := <-done; !errors.Is(e, io.EOF) {
		t.Fatal(e)
	}
	if count.Load() != 2 || clicks.Load() != 1 {
		t.Fatal(count.Load(), clicks.Load())
	}
}
