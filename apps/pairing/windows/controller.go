package main

import (
	"bufio"
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"math/big"
	"net"
	"time"
)

// The out-of-band key authenticates the host, never its advertised label or IP.
func connectHost(key string, selected *net.TCPAddr, pending func(string)) (*tls.Conn, *bufio.Reader, error) {
	if len(key) != 55 || key[:4] != "NP1-" {
		return nil, nil, errors.New("enter the full NP1 device key")
	}
	b, e := b64.DecodeString(key[4:])
	if e != nil || len(b) != 38 {
		return nil, nil, errors.New("invalid key")
	}
	addr := &net.TCPAddr{IP: net.IP(b[:4]), Port: int(binary.BigEndian.Uint16(b[4:6]))}
	if (!addr.IP.IsPrivate() && !addr.IP.IsLoopback()) || addr.Port == 0 {
		return nil, nil, errors.New("not a LAN invitation")
	}
	if selected != nil && (!addr.IP.Equal(selected.IP) || addr.Port != selected.Port) {
		return nil, nil, errors.New("key is for a different host or an expired invitation; scan again")
	}
	cfg := &tls.Config{MinVersion: tls.VersionTLS12, InsecureSkipVerify: true, // verify the invite fingerprint instead of using public CAs
		VerifyConnection: func(cs tls.ConnectionState) error {
			if len(cs.PeerCertificates) > 0 {
				h := sha256.Sum256(cs.PeerCertificates[0].Raw)
				if bytes.Equal(h[:16], b[6:22]) {
					return nil
				}
			}
			return errors.New("certificate does not match device key")
		}}
	c, e := tls.DialWithDialer(&net.Dialer{Timeout: 5 * time.Second}, "tcp4", addr.String(), cfg)
	if e != nil {
		return nil, nil, e
	}
	r := bufio.NewReaderSize(c, 128)
	c.SetDeadline(time.Now().Add(35 * time.Second))
	v, e := rand.Int(rand.Reader, big.NewInt(1000000))
	if e != nil {
		c.Close()
		return nil, nil, e
	}
	code := fmt.Sprintf("%06d", v.Int64())
	if _, e = fmt.Fprintf(c, "NP1 %s %s\n", b64.EncodeToString(b[22:]), code); e == nil {
		pending(code)
		var reply string
		reply, e = readLine(r)
		if e == nil && reply != "APPROVED VOLUME" {
			e = errors.New("owner denied or invitation ended")
		}
	}
	if e != nil {
		c.Close()
		return nil, nil, e
	}
	c.SetDeadline(time.Now().Add(sessionTTL))
	return c, r, nil
}
