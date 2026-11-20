package main

import (
	"context"
	qr "nodeport.local/pairing/internal/qrcode"
	"runtime"
	"syscall"
	"unsafe"
)

// An in-memory QR window: no key-bearing PNG is written to disk. Default
// window resizing never crops the four-module quiet zone returned by Bitmap.
type winRect struct{ Left, Top, Right, Bottom int32 }
type winClass struct {
	Style                                          uint32
	Proc                                           uintptr
	ClsExtra, WndExtra                             int32
	Instance, Icon, Cursor, Background, Menu, Name uintptr
}
type winMsg struct {
	Window  uintptr
	Message uint32
	W, L    uintptr
	Time    uint32
	X, Y    int32
	Private uint32
}
type paintStruct struct {
	DC                 uintptr
	Erase              int32
	Rect               winRect
	Restore, IncUpdate int32
	Reserved           [32]byte
}

func showQR(ctx context.Context, key string) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	code, e := qr.New(key, qr.Medium)
	if e != nil {
		return
	}
	cells := code.Bitmap()
	class, _ := syscall.UTF16PtrFromString("NodeportInviteQR")
	title, _ := syscall.UTF16PtrFromString("Nodeport Link — scan this private invitation")
	inst, _, _ := syscall.NewLazyDLL("kernel32.dll").NewProc("GetModuleHandleW").Call(0)
	white, _, _ := gdi.NewProc("GetStockObject").Call(0)
	black, _, _ := gdi.NewProc("GetStockObject").Call(4)
	proc := syscall.NewCallback(func(hwnd uintptr, msg uint32, w, l uintptr) uintptr {
		switch msg {
		case 15:
			var paint paintStruct
			dc, _, _ := user32.NewProc("BeginPaint").Call(hwnd, uintptr(unsafe.Pointer(&paint)))
			var r winRect
			user32.NewProc("GetClientRect").Call(hwnd, uintptr(unsafe.Pointer(&r)))
			fill := user32.NewProc("FillRect")
			fill.Call(dc, uintptr(unsafe.Pointer(&r)), white)
			n := int32(len(cells))
			size := min(r.Right, r.Bottom) / n
			ox, oy := (r.Right-n*size)/2, (r.Bottom-n*size)/2
			for y, row := range cells {
				for x, dark := range row {
					if dark {
						px, py := ox+int32(x)*size, oy+int32(y)*size
						part := winRect{px, py, px + size, py + size}
						fill.Call(dc, uintptr(unsafe.Pointer(&part)), black)
					}
				}
			}
			user32.NewProc("EndPaint").Call(hwnd, uintptr(unsafe.Pointer(&paint)))
			return 0
		case 2:
			user32.NewProc("PostQuitMessage").Call(0)
			return 0
		}
		ret, _, _ := user32.NewProc("DefWindowProcW").Call(hwnd, uintptr(msg), w, l)
		return ret
	})
	c := winClass{Style: 3, Proc: proc, Instance: inst, Background: white, Name: uintptr(unsafe.Pointer(class))}
	atom, _, _ := user32.NewProc("RegisterClassW").Call(uintptr(unsafe.Pointer(&c)))
	if atom == 0 {
		return
	}
	defer user32.NewProc("UnregisterClassW").Call(uintptr(unsafe.Pointer(class)), inst)
	hwnd, _, _ := user32.NewProc("CreateWindowExW").Call(0, uintptr(unsafe.Pointer(class)), uintptr(unsafe.Pointer(title)), 0x10cf0000, 0x80000000, 0x80000000, 440, 470, 0, 0, inst, 0)
	if hwnd == 0 {
		return
	}
	stop := context.AfterFunc(ctx, func() { user32.NewProc("PostMessageW").Call(hwnd, 0x10, 0, 0) })
	defer stop()
	var m winMsg
	for {
		v, _, _ := user32.NewProc("GetMessageW").Call(uintptr(unsafe.Pointer(&m)), 0, 0, 0)
		if int32(v) <= 0 {
			return
		}
		user32.NewProc("TranslateMessage").Call(uintptr(unsafe.Pointer(&m)))
		user32.NewProc("DispatchMessageW").Call(uintptr(unsafe.Pointer(&m)))
	}
}
