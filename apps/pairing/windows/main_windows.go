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
func askOwner(p context.Context, peer, code, grant string) bool {
	fmt.Printf("\aIncoming request from %s for %s. Compare %s and answer the Windows prompt.\n", peer, grant, code)
	result := make(chan bool, 1)
	go func() {
		caption, _ := syscall.UTF16PtrFromString("Nodeport Link — Pair request")
		msg, _ := syscall.UTF16PtrFromString(fmt.Sprintf("%s requests: %s.\n\nDoes the phone show %s? Choose No if you did not expect this request.\n\nAllow this session?", peer, grant, code))
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
func control(in *bufio.Scanner, selected *net.TCPAddr) {
	fmt.Print("Paste the target owner’s private NP1 key: ")
	if !in.Scan() {
		return
	}
	c, r, e := connectHost(strings.TrimSpace(in.Text()), selected, func(code string) { fmt.Println("Compare", code, "with the owner. Waiting for approval…") })
	if e != nil {
		fmt.Println("Connection failed:", e)
		fmt.Print("Check the private LAN, firewall and key expiry. Press Enter to exit. ")
		in.Scan()
		return
	}
	defer c.Close()
	fmt.Println("Approved for volume only. Type UP, DOWN, or STOP, then Enter.")
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			reply, e := readLine(r)
			if e != nil {
				return
			}
			switch reply {
			case "OK":
				fmt.Println("Volume request accepted.")
			case "WAIT":
				fmt.Println("Wait briefly and try again.")
			case "FAILED":
				fmt.Println("The host could not change volume.")
			default:
				return
			}
		}
	}()
	go func() {
		defer c.Close()
		for in.Scan() {
			cmd := strings.ToUpper(strings.TrimSpace(in.Text()))
			switch cmd {
			case "UP", "DOWN":
				if _, e := fmt.Fprintln(c, cmd); e != nil {
					return
				}
			case "STOP":
				return
			default:
				fmt.Println("Use UP, DOWN or STOP.")
			}
		}
	}()
	<-done
	fmt.Println("Disconnected.")
}
func main() {
	user32.NewProc("SetProcessDPIAware").Call()
	fmt.Println("NODEPORT LINK 0.4  |  Nearby devices\n1. Host — receive a request on this PC\n2. Scan nearby Nodeport hosts\n3. Connect directly with a key")
	in := bufio.NewScanner(os.Stdin)
	fmt.Print("Choose 1, 2 or 3: ")
	if !in.Scan() {
		return
	}
	mode := strings.TrimSpace(in.Text())
	if mode == "3" {
		control(in, nil)
		return
	}
	if mode != "1" && mode != "2" {
		return
	}
	var lans []*net.IPNet
	all, _ := net.InterfaceAddrs()
	for _, a := range all {
		if n, ok := a.(*net.IPNet); ok && n.IP.To4() != nil && n.IP.IsPrivate() {
			lans = append(lans, n)
			fmt.Printf("%d. %s\n", len(lans), n.IP)
		}
	}
	if len(lans) == 0 {
		fmt.Println("No private IPv4 interface. Connect to Wi-Fi or Ethernet. Press Enter to exit.")
		in.Scan()
		return
	}
	i := 1
	if len(lans) > 1 {
		fmt.Print("Choose LAN interface: ")
		if !in.Scan() {
			return
		}
		i, _ = strconv.Atoi(strings.TrimSpace(in.Text()))
	}
	if i < 1 || i > len(lans) {
		return
	}
	lan := lans[i-1]
	ip := lan.IP
	if mode == "2" {
		for {
			fmt.Println("Listening for Nodeport hosts for six seconds on", ip)
			ps, e := scan(lan)
			if e != nil {
				fmt.Println("Discovery failed:", e)
			} else if len(ps) == 0 {
				fmt.Println("No hosts found. Open Receive a request on the target. Guest Wi-Fi may block discovery.")
			} else {
				for j, p := range ps {
					fmt.Printf("%d. %s  (%s)\n", j+1, p.name, p.endpoint)
				}
			}
			fmt.Print("Select number, R to rescan, K for direct key, or Enter to exit: ")
			if !in.Scan() {
				return
			}
			choice := strings.ToUpper(strings.TrimSpace(in.Text()))
			if choice == "R" {
				continue
			}
			if choice == "K" {
				control(in, nil)
				return
			}
			j, _ := strconv.Atoi(choice)
			if j >= 1 && j <= len(ps) {
				control(in, ps[j-1].endpoint)
			}
			return
		}
	}
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
	ads, stopAds := context.WithTimeout(ctx, inviteTTL)
	defer stopAds()
	name, _ := os.Hostname()
	go func() {
		if err := advertise(ads, lan, l.Addr().(*net.TCPAddr).Port, name); err != nil {
			fmt.Println("Not advertising; manual key still works:", err)
		}
	}()
	go showQR(ads, v.key)
	desktop := &desktop{}
	screen := &screenAdapter{capture: desktop.capture, click: desktop.click,
		approve: func(p context.Context, peer, code, scope string) bool {
			stopAds()
			grant := "view your entire primary display (no input)"
			if scope == "SCREEN_CLICK" {
				grant = "view your entire primary display AND left-click applications as you"
			}
			return askOwner(p, peer, code, grant)
		},
		started: func(peer, scope string) {
			fmt.Printf("ACTIVE: %s — %s. Type STOP or close this window to revoke.\n", peer, scope)
			go func() {
				caption, _ := syscall.UTF16PtrFromString("Nodeport Link — sharing active")
				msg, _ := syscall.UTF16PtrFromString("Sharing your primary display with " + peer + " (" + scope + "). Choose OK to stop sharing now.")
				user32.NewProc("MessageBoxW").Call(0, uintptr(unsafe.Pointer(msg)), uintptr(unsafe.Pointer(caption)), 0x40040)
				cancel()
			}()
		},
	}
	e = serveWithScreen(ctx, l, v, func(p context.Context, peer, code string) bool {
		stopAds()
		return askOwner(p, peer, code, "volume up/down only")
	}, adjust, func(peer string) { fmt.Printf("ACTIVE: %s can change volume. Type STOP to revoke.\n", peer) }, screen)
	cancel()
	fmt.Println("\nSharing stopped.")
	if e != nil {
		fmt.Println(e)
	}
}
