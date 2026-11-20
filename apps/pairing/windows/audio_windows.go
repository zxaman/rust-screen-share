// Only the two step operations on the default multimedia playback endpoint are
// exposed. No input injection, shell, microphone or audio capture is involved.
package main

import (
	"fmt"
	"runtime"
	"syscall"
	"unsafe"
)

var ole = syscall.NewLazyDLL("ole32.dll")
var initialize = ole.NewProc("CoInitializeEx")
var uninitialize = ole.NewProc("CoUninitialize")
var createInstance = ole.NewProc("CoCreateInstance")
var clsEnumerator = syscall.GUID{Data1: 0xbcde0395, Data2: 0xe52f, Data3: 0x467c, Data4: [8]byte{0x8e, 0x3d, 0xc4, 0x57, 0x92, 0x91, 0x69, 0x2e}}
var iidEnumerator = syscall.GUID{Data1: 0xa95664d2, Data2: 0x9614, Data3: 0x4f35, Data4: [8]byte{0xa7, 0x46, 0xde, 0x8d, 0xb6, 0x36, 0x17, 0xe6}}
var iidVolume = syscall.GUID{Data1: 0x5cdf2c82, Data2: 0x841e, Data3: 0x4546, Data4: [8]byte{0x97, 0x22, 0x0c, 0xf7, 0x40, 0x78, 0x22, 0x9a}}

// Windows SDK vtable slots from mmdeviceapi.h and endpointvolume.h. All
// interface calls and releases occur on the same initialized apartment thread.
type comObject struct{ table *[20]uintptr }

func (obj *comObject) release() { syscall.SyscallN(obj.table[2], uintptr(unsafe.Pointer(obj))) }
func hr(v uintptr) error {
	if int32(v) < 0 {
		return fmt.Errorf("Core Audio HRESULT 0x%08x", uint32(v))
	}
	return nil
}
func adjust(cmd string) error {
	slot := 17 // IAudioEndpointVolume::VolumeStepUp
	if cmd == "DOWN" {
		slot = 18
	} else if cmd != "UP" {
		return fmt.Errorf("unsupported operation")
	}
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	v, _, _ := initialize.Call(0, 2)
	if err := hr(v); err != nil {
		return err
	}
	defer uninitialize.Call()
	var enumerator, device, volume *comObject
	v, _, _ = createInstance.Call(uintptr(unsafe.Pointer(&clsEnumerator)), 0, 1, uintptr(unsafe.Pointer(&iidEnumerator)), uintptr(unsafe.Pointer(&enumerator)))
	if err := hr(v); err != nil {
		return err
	}
	defer enumerator.release()
	// IMMDeviceEnumerator::GetDefaultAudioEndpoint(eRender, eMultimedia)
	v, _, _ = syscall.SyscallN(enumerator.table[4], uintptr(unsafe.Pointer(enumerator)), 0, 1, uintptr(unsafe.Pointer(&device)))
	if err := hr(v); err != nil {
		return err
	}
	defer device.release()
	// IMMDevice::Activate(IID_IAudioEndpointVolume, CLSCTX_ALL)
	v, _, _ = syscall.SyscallN(device.table[3], uintptr(unsafe.Pointer(device)), uintptr(unsafe.Pointer(&iidVolume)), 23, 0, uintptr(unsafe.Pointer(&volume)))
	if err := hr(v); err != nil {
		return err
	}
	defer volume.release()
	v, _, _ = syscall.SyscallN(volume.table[slot], uintptr(unsafe.Pointer(volume)), 0)
	return hr(v)
}
