package main

import (
	"bytes"
	"errors"
	"image"
	"image/jpeg"
	"runtime"
	"strings"
	"syscall"
	"unsafe"
)

var gdi = syscall.NewLazyDLL("gdi32.dll")
var metrics = user32.NewProc("GetSystemMetrics")

type bitmapInfo struct {
	Size                    uint32
	Width, Height           int32
	Planes, Bits            uint16
	Compression, ImageBytes uint32
	X, Y                    int32
	Colors, Important       uint32
}
type desktop struct{ lastW, lastH int }

func dimensions() (int, int) {
	w, _, _ := metrics.Call(0)
	h, _, _ := metrics.Call(1)
	return int(w), int(h)
}

// Do not capture or inject into a lock screen, UAC prompt, or alternative desktop.
func ordinaryDesktop() bool {
	desk, _, _ := user32.NewProc("OpenInputDesktop").Call(0, 0, 1)
	if desk == 0 {
		return false
	}
	defer user32.NewProc("CloseDesktop").Call(desk)
	var name [128]uint16
	var needed uint32
	ok, _, _ := user32.NewProc("GetUserObjectInformationW").Call(desk, 2, uintptr(unsafe.Pointer(&name[0])), unsafe.Sizeof(name), uintptr(unsafe.Pointer(&needed)))
	return ok != 0 && strings.EqualFold(syscall.UTF16ToString(name[:]), "Default")
}

// Capture the visible primary display in the interactive user's desktop; no
// drivers, services, elevation or access to protected/secure desktops.
func (d *desktop) capture() ([]byte, error) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	if !ordinaryDesktop() {
		return nil, errors.New("display locked or unavailable")
	}
	w, h := dimensions()
	if w < 1 || h < 1 || w > 32768 || h > 32768 {
		return nil, errors.New("no primary desktop")
	}
	sw, sh := w, h
	if sw > 960 {
		sw = 960
		sh = max(1, h*960/w)
	}
	if sh > 720 {
		sh = 720
		sw = max(1, w*720/h)
	}
	src, _, _ := user32.NewProc("GetDC").Call(0)
	if src == 0 {
		return nil, errors.New("display unavailable")
	}
	defer user32.NewProc("ReleaseDC").Call(0, src)
	dst, _, _ := gdi.NewProc("CreateCompatibleDC").Call(src)
	if dst == 0 {
		return nil, errors.New("cannot allocate capture context")
	}
	defer gdi.NewProc("DeleteDC").Call(dst)
	info := bitmapInfo{Size: 40, Width: int32(sw), Height: -int32(sh), Planes: 1, Bits: 32}
	var pixels unsafe.Pointer
	bmp, _, _ := gdi.NewProc("CreateDIBSection").Call(src, uintptr(unsafe.Pointer(&info)), 0, uintptr(unsafe.Pointer(&pixels)), 0, 0)
	if bmp == 0 || pixels == nil {
		return nil, errors.New("cannot allocate frame")
	}
	defer gdi.NewProc("DeleteObject").Call(bmp)
	old, _, _ := gdi.NewProc("SelectObject").Call(dst, bmp)
	if old == 0 || old == ^uintptr(0) {
		return nil, errors.New("cannot select capture bitmap")
	}
	defer gdi.NewProc("SelectObject").Call(dst, old)
	gdi.NewProc("SetStretchBltMode").Call(dst, 4)
	ok, _, _ := gdi.NewProc("StretchBlt").Call(dst, 0, 0, uintptr(sw), uintptr(sh), src, 0, 0, uintptr(w), uintptr(h), 0x00CC0020)
	if ok == 0 {
		return nil, errors.New("Windows refused desktop capture")
	}
	gdi.NewProc("GdiFlush").Call()
	img := image.NewRGBA(image.Rect(0, 0, sw, sh))
	p := unsafe.Slice((*byte)(pixels), sw*sh*4)
	for i := 0; i < len(p); i += 4 {
		img.Pix[i] = p[i+2]
		img.Pix[i+1] = p[i+1]
		img.Pix[i+2] = p[i]
		img.Pix[i+3] = 255
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 60}); err != nil {
		return nil, err
	}
	d.lastW, d.lastH = w, h
	return buf.Bytes(), nil
}

// INPUT/MOUSEINPUT for the supported x64 artifact (40 bytes).
type mouseInput struct {
	Kind, Pad               uint32
	X, Y                    int32
	Data, Flags, Time, Pad2 uint32
	Extra                   uintptr
}

func (d *desktop) click(x, y int) error {
	if !ordinaryDesktop() {
		return errors.New("desktop locked or unavailable")
	}
	w, h := dimensions()
	if w != d.lastW || h != d.lastH {
		return errors.New("display configuration changed")
	}
	if x < 0 || y < 0 || x > 10000 || y > 10000 {
		return errors.New("invalid point")
	}
	events := [3]mouseInput{{X: int32(x * 65535 / 10000), Y: int32(y * 65535 / 10000), Flags: 0x8001}, {Flags: 2}, {Flags: 4}}
	n, _, _ := user32.NewProc("SendInput").Call(3, uintptr(unsafe.Pointer(&events[0])), unsafe.Sizeof(events[0]))
	if n != 3 {
		return errors.New("Windows refused click")
	}
	return nil
}
