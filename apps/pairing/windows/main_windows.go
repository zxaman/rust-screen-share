package main

import (
	"bufio"
	"context"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"syscall"
	"unsafe"
)

var user32 = syscall.NewLazyDLL("user32.dll")

// The native owner prompt is separate from the controller and defaults to No.
func askOwner(p context.Context, peer, code string) bool {
	fmt.Printf("\aIncoming volume-control request from %s. Compare %s and answer the Windows prompt.\n", peer, code)
	result := make(chan bool, 1)
	go func() {
		caption, _ := syscall.UTF16PtrFromString("Nodeport Link — Pair request")
		msg, _ := syscall.UTF16PtrFromString(fmt.Sprintf("%s requests volume up/down control of this PC.\n\nDoes the phone show %s? Choose No if you did not expect this request.\n\nAllow this session?", peer, code))
		button, _, _ := user32.NewProc("MessageBoxW").Call(0, uintptr(unsafe.Pointer(msg)), uintptr(unsafe.Pointer(caption)), 0x04|0x20|0x100|0x10000|0x40000)
		result <- button == 6 // IDYES; all other results fail closed
	}()
	select {
	case allowed := <-result:
		return allowed
	case <-p.Done():
		return false
	}
}
func main() {
	fmt.Println("NODEPORT LINK  |  Windows volume host\nOnly start this on a trusted private LAN. Closing this window stops sharing.")
	in := bufio.NewScanner(os.Stdin)
	var ips []net.IP
	all, _ := net.InterfaceAddrs()
	for _, a := range all {
		if n, ok := a.(*net.IPNet); ok && n.IP.To4() != nil && n.IP.IsPrivate() {
			ips = append(ips, n.IP.To4())
			fmt.Printf("%d. %s\n", len(ips), n.IP)
		}
	}
	if len(ips) == 0 {
		fmt.Println("No private IPv4 interface. Connect to Wi-Fi or Ethernet. Press Enter to exit.")
		in.Scan()
		return
	}
	fmt.Print("Choose LAN interface number to create an invitation: ")
	if !in.Scan() {
		return
	}
	i, e := strconv.Atoi(strings.TrimSpace(in.Text()))
	if e != nil || i < 1 || i > len(ips) {
		return
	}
	ip := ips[i-1]
	l, e := net.ListenTCP("tcp4", &net.TCPAddr{IP: ip})
	if e != nil {
		fmt.Println(e)
		in.Scan()
		return
	}
	defer l.Close()
	v, e := newInvite(ip, l.Addr().(*net.TCPAddr).Port)
	if e != nil {
		fmt.Println(e)
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() {
		for in.Scan() {
			line := strings.TrimSpace(in.Text())
			if strings.EqualFold(line, "stop") {
				cancel()
				return
			}

		}
		cancel()
	}()
	fmt.Printf("\nOne-time device key (2 minutes):\n%s\n\nSend privately to the phone operator. Type STOP at any time.\n", v.key)
	e = serve(ctx, l, v, askOwner, adjust, func(peer string) { fmt.Printf("\nACTIVE: %s can change volume. Type STOP to revoke.\n", peer) })
	cancel()
	fmt.Println("\nSharing stopped.")
	if e != nil {
		fmt.Println(e)
	}
}
