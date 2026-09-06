package com.drmhse.dream.fit

// Wire contract, both ends. Newline-delimited UTF-8 JSON, chunked to MTU-3.
//
// watch -> phone
//   {"t":"hr","bpm":72}
//   {"t":"day","d":"2026-09-05","steps":8412,"rhr":54,"exmin":22,"bat":73}
//
// phone -> watch
//   {"t":"notify","title":"...","body":"..."}
//   {"t":"sync"}                       -- asks for a fresh `day` right now
//
// The watch owns every daily aggregate: `day` is absolute, idempotent, and
// carries the watch's own local date so the phone never guesses a boundary.
//
// Every characteristic here requires an encrypted, MITM-protected link. The
// bond the phone already holds for ANCS satisfies that, so this costs nothing
// at runtime — but it means the service is unreachable to anything that is not
// this paired phone. Health data does not travel in the clear.
object BridgeGatt {
    const val SERVICE = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    const val RX = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" // phone -> watch, write
    const val TX = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" // watch -> phone, notify
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
}
