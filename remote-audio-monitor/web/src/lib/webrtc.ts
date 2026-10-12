import {
  collection,
  addDoc,
  onSnapshot,
  query,
  where,
  orderBy,
  serverTimestamp,
  Timestamp,
} from "firebase/firestore";
import { db } from "./firebase";

export type SignalRole = "phone" | "dashboard";

interface SignalMessage {
  type: "offer" | "answer" | "ice-candidate";
  payload: unknown;
  senderRole: SignalRole;
  createdAt?: Timestamp;
  expiresAt: Timestamp;
}

async function fetchIceServers(): Promise<RTCIceServer[]> {
  const res = await fetch("/api/turn-credentials");
  if (!res.ok) {
    // Fall back to STUN-only if TURN credentials are unavailable — some
    // connections (same network, no strict NAT) will still work.
    return [{ urls: "stun:stun.l.google.com:19302" }];
  }
  const data = await res.json();
  return data.iceServers;
}

function expiresInMinutes(minutes: number): Timestamp {
  return Timestamp.fromDate(new Date(Date.now() + minutes * 60 * 1000));
}

async function sendSignal(
  sessionId: string,
  role: SignalRole,
  type: SignalMessage["type"],
  payload: unknown
) {
  await addDoc(collection(db, "sessions", sessionId, "messages"), {
    type,
    payload,
    senderRole: role,
    createdAt: serverTimestamp(),
    expiresAt: expiresInMinutes(30),
  });
}

/**
 * Dashboard-side WebRTC session. Listens for an offer from the phone,
 * answers it, exchanges ICE candidates, and exposes the received audio
 * stream via onRemoteStream.
 */
export class DashboardWebRTCSession {
  private pc: RTCPeerConnection | null = null;
  private unsubscribe: (() => void) | null = null;
  private answered = false;
  private stopped = false;

  async start(sessionId: string, onRemoteStream: (stream: MediaStream) => void) {
    const iceServers = await fetchIceServers();
    if (this.stopped) return;
    const pc = new RTCPeerConnection({ iceServers });
    this.pc = pc;

    pc.ontrack = (event) => {
      if (event.streams[0]) onRemoteStream(event.streams[0]);
    };

    pc.onicecandidate = (event) => {
      if (event.candidate) {
        sendSignal(sessionId, "dashboard", "ice-candidate", event.candidate.toJSON()).catch(
          (e) => console.error("send ICE failed", e)
        );
      }
    };

    const q = query(
      collection(db, "sessions", sessionId, "messages"),
      where("senderRole", "==", "phone"),
      orderBy("createdAt", "asc")
    );

    let chain: Promise<void> = Promise.resolve();
    let remoteSet = false;
    const pending: RTCIceCandidateInit[] = [];

    this.unsubscribe = onSnapshot(
      q,
      (snapshot) => {
        snapshot.docChanges().forEach((change) => {
          if (change.type !== "added") return;
          const data = change.doc.data() as SignalMessage;
          chain = chain
            .then(async () => {
              if (this.stopped) return;
              if (data.type === "offer" && !this.answered) {
                this.answered = true;
                await pc.setRemoteDescription(
                  new RTCSessionDescription(data.payload as RTCSessionDescriptionInit)
                );
                remoteSet = true;
                for (const c of pending.splice(0)) {
                  await pc.addIceCandidate(new RTCIceCandidate(c)).catch(() => {});
                }
                const answer = await pc.createAnswer();
                await pc.setLocalDescription(answer);
                await sendSignal(sessionId, "dashboard", "answer", {
                  type: answer.type,
                  sdp: answer.sdp,
                });
              } else if (data.type === "ice-candidate") {
                const cand = data.payload as RTCIceCandidateInit;
                if (!remoteSet) pending.push(cand);
                else await pc.addIceCandidate(new RTCIceCandidate(cand)).catch(() => {});
              }
            })
            .catch((e) => console.error("signaling error", e));
        });
      },
      (err) => console.error("signaling listener error", err)
    );
  }

  stop() {
    this.stopped = true;
    this.unsubscribe?.();
    this.unsubscribe = null;
    this.pc?.close();
    this.pc = null;
    this.answered = false;
  }
}
