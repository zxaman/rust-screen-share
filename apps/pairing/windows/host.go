// NP1: one invite, one foreground approval, one short-lived volume-only session.
package main

import (
	"bufio"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"math/big"
	"net"
	"strings"
	"time"
)

const inviteTTL = 2 * time.Minute
const sessionTTL = 10 * time.Minute

var b64 = base64.RawURLEncoding

type invite struct {
	config *tls.Config
	secret string
	key    string
}

func newInvite(ip net.IP, port int) (invite, error) {
	var v invite
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return v, err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return v, err
	}
	cert := &x509.Certificate{Subject: pkix.Name{CommonName: "Nodeport Link"}, SerialNumber: serial, NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, cert, cert, &k.PublicKey, k)
	if err != nil {
		return v, err
	}
	payload := make([]byte, 38)
	copy(payload, ip.To4())
	binary.BigEndian.PutUint16(payload[4:6], uint16(port))
	pin := sha256.Sum256(der)
	copy(payload[6:22], pin[:16])
	if _, err = rand.Read(payload[22:]); err != nil {
		return v, err
	}
	v = invite{&tls.Config{MinVersion: tls.VersionTLS12, Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: k}}}, b64.EncodeToString(payload[22:]), "NP1-" + b64.EncodeToString(payload)}
	return v, nil
}

type approval func(context.Context, string, string) bool

// ctx is cancelled by the owner's Stop action. Closing both listener and current
// socket unblocks accept, TLS handshake and reads, including while awaiting consent.
func serve(ctx context.Context, l net.Listener, v invite, approve approval, volume func(string) error, active func(string)) error {
	return serveWithScreen(ctx, l, v, approve, volume, active, nil)
}
func serveWithScreen(ctx context.Context, l net.Listener, v invite, approve approval, volume func(string) error, active func(string), screen *screenAdapter) error {
	defer l.Close()
	stop := context.AfterFunc(ctx, func() { l.Close() })
	defer stop()
	expires := time.Now().Add(inviteTTL)
	timer := time.AfterFunc(inviteTTL, func() { l.Close() })
	defer timer.Stop()
	for attempt := 0; attempt < 5; attempt++ {
		raw, err := l.Accept()
		if err != nil {
			return err
		}
		conn := tls.Server(raw, v.config)
		cancel := context.AfterFunc(ctx, func() { conn.Close() })
		conn.SetDeadline(minTime(expires, time.Now().Add(5*time.Second)))
		r := bufio.NewReaderSize(conn, 128)
		line, err := readLine(r)
		parts := strings.Split(line, " ")
		scope := "VOLUME"
		valid := len(parts) == 3 && parts[0] == "NP1"
		if len(parts) == 4 && parts[0] == "NP2" && screen != nil && (parts[3] == "SCREEN" || parts[3] == "SCREEN_CLICK") {
			scope = parts[3]
			valid = true
		}
		if err != nil || !valid || !isCode(parts[2]) || !time.Now().Before(expires) {
			conn.Close()
			cancel()
			continue
		}
		if subtle.ConstantTimeCompare([]byte(parts[1]), []byte(v.secret)) != 1 {
			fmt.Fprintln(conn, "BAD_INVITE")
			conn.Close()
			cancel()
			continue
		}
		defer cancel()
		defer conn.Close()
		l.Close()
		timer.Stop() // consume the invitation, even if denied
		prompt, closePrompt := context.WithDeadline(ctx, minTime(expires, time.Now().Add(30*time.Second)))
		defer closePrompt()
		peer := raw.RemoteAddr().String()
		var allowed bool
		if scope == "VOLUME" {
			allowed = approve(prompt, peer, parts[2])
		} else {
			allowed = screen.approve(prompt, peer, parts[2], scope)
		}
		conn.SetDeadline(time.Now().Add(3 * time.Second))
		if !allowed || prompt.Err() != nil {
			fmt.Fprintln(conn, "DENIED")
			return errors.New("request denied or expired")
		}
		if _, err = fmt.Fprintln(conn, "APPROVED "+scope); err != nil {
			return err
		}
		if scope != "VOLUME" {
			screen.started(peer, scope)
			return screenLoop(ctx, conn, r, scope, screen)
		}
		active(peer)
		end := time.Now().Add(sessionTTL)
		var last time.Time
		for {
			conn.SetDeadline(minTime(end, time.Now().Add(time.Minute)))
			cmd, err := readLine(r)
			if err != nil {
				return err
			}
			if ctx.Err() != nil {
				return ctx.Err()
			}
			if !time.Now().Before(end) {
				return errors.New("session expired")
			}
			switch cmd {
			case "STOP":
				return nil
			case "UP", "DOWN":
			default:
				return errors.New("unsupported operation")
			}
			reply := "OK"
			if time.Since(last) < 300*time.Millisecond {
				reply = "WAIT"
			} else {
				last = time.Now()
				if err := volume(cmd); err != nil {
					reply = "FAILED"
				}
			}
			if _, err = fmt.Fprintln(conn, reply); err != nil {
				return err
			}
		}
	}
	return errors.New("too many unsuccessful requests")
}
func minTime(a, b time.Time) time.Time {
	if a.Before(b) {
		return a
	}
	return b
}
func readLine(r *bufio.Reader) (string, error) {
	b, e := r.ReadSlice('\n')
	if e != nil {
		return "", e
	}
	return string(b[:len(b)-1]), nil
}
func isCode(s string) bool {
	if len(s) != 6 {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}
