package main

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"
	"time"
)

type screenAdapter struct {
	approve func(context.Context, string, string, string) bool
	started func(string, string)
	capture func() ([]byte, error)
	click   func(int, int) error
}

const maxJPEG = 512 * 1024

// A screen session is distinct from volume. Only the explicitly approved
// SCREEN_CLICK grant accepts a normalized click following a recent frame.
func screenLoop(ctx context.Context, conn interface {
	io.Writer
	SetDeadline(time.Time) error
}, r *bufio.Reader, scope string, s *screenAdapter) error {
	end := time.Now().Add(sessionTTL)
	var frame, lastClick time.Time
	for {
		conn.SetDeadline(minTime(end, time.Now().Add(time.Minute)))
		line, err := readLine(r)
		if err != nil {
			return err
		}
		if err = ctx.Err(); err != nil {
			return err
		}
		if !time.Now().Before(end) {
			return errors.New("session expired")
		}
		switch {
		case line == "STOP":
			return nil
		case line == "FRAME":
			if time.Since(frame) < 200*time.Millisecond {
				return errors.New("too many frame requests")
			}
			jpg, e := s.capture()
			if e != nil {
				return e
			}
			if len(jpg) == 0 || len(jpg) > maxJPEG {
				return errors.New("frame size out of bounds")
			}
			if ctx.Err() != nil {
				return ctx.Err()
			}
			if _, e = fmt.Fprintf(conn, "JPEG %d\n", len(jpg)); e != nil {
				return e
			}
			if _, e = conn.Write(jpg); e != nil {
				return e
			}
			frame = time.Now()
		case scope == "SCREEN_CLICK" && strings.HasPrefix(line, "CLICK "):
			fields := strings.Split(line, " ")
			if len(fields) != 3 || time.Since(frame) > 3*time.Second || time.Since(lastClick) < 300*time.Millisecond {
				return errors.New("invalid or stale click")
			}
			x, ex := strconv.Atoi(fields[1])
			y, ey := strconv.Atoi(fields[2])
			if ex != nil || ey != nil || x < 0 || y < 0 || x > 10000 || y > 10000 {
				return errors.New("click outside desktop")
			}
			if err = s.click(x, y); err != nil {
				return err
			}
			lastClick = time.Now()
			if _, err = fmt.Fprintln(conn, "OK"); err != nil {
				return err
			}
		default:
			return errors.New("operation not granted for this session")
		}
	}
}
