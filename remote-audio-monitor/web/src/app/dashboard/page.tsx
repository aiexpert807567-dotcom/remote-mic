"use client";

import { useEffect, useState } from "react";
import {
  collection,
  query,
  where,
  onSnapshot,
  doc,
  deleteDoc,
  addDoc,
  serverTimestamp,
  orderBy,
  limit,
} from "firebase/firestore";
import { signOut } from "firebase/auth";
import { db, auth } from "@/lib/firebase";
import { useAuth } from "@/lib/auth-context";
import ProtectedRoute from "@/components/ProtectedRoute";

interface DeviceDoc {
  id: string;
  displayName: string;
  status: "online" | "offline" | "listening" | "unknown";
  pairedAt?: { seconds: number };
  lastSeenAt?: { seconds: number };
}

interface SessionDoc {
  id: string;
  deviceId: string;
  ownerId: string;
  state: "requested" | "connecting" | "active" | "ended" | "error";
  requestedAt?: { seconds: number };
}

function DashboardContent() {
  const { user } = useAuth();
  const [devices, setDevices] = useState<DeviceDoc[]>([]);
  const [selectedDeviceId, setSelectedDeviceId] = useState<string | null>(null);
  const [pairingToken, setPairingToken] = useState<string | null>(null);
  const [generatingToken, setGeneratingToken] = useState(false);
  const [activeSession, setActiveSession] = useState<SessionDoc | null>(null);
  const [requestingSession, setRequestingSession] = useState(false);

  useEffect(() => {
    if (!user) return;
    const q = query(collection(db, "devices"), where("ownerId", "==", user.uid));
    const unsubscribe = onSnapshot(q, (snapshot) => {
      const list: DeviceDoc[] = snapshot.docs.map((d) => ({
        id: d.id,
        ...(d.data() as Omit<DeviceDoc, "id">),
      }));
      setDevices(list);
    });
    return () => unsubscribe();
  }, [user]);

  // Watch for the most recent session tied to the selected device, so the
  // UI reflects real state (requested -> connecting -> active -> ended)
  // rather than only what the dashboard itself just asked for.
  useEffect(() => {
    if (!user || !selectedDeviceId) {
      setActiveSession(null);
      return;
    }
    const q = query(
      collection(db, "sessions"),
      where("ownerId", "==", user.uid),
      where("deviceId", "==", selectedDeviceId),
      orderBy("requestedAt", "desc"),
      limit(1)
    );
    const unsubscribe = onSnapshot(q, (snapshot) => {
      if (snapshot.empty) {
        setActiveSession(null);
        return;
      }
      const docSnap = snapshot.docs[0];
      const data = docSnap.data() as Omit<SessionDoc, "id">;
      // Only treat it as "active" in the UI if it hasn't ended.
      setActiveSession(data.state === "ended" ? null : { id: docSnap.id, ...data });
    });
    return () => unsubscribe();
  }, [user, selectedDeviceId]);

  async function handleRemoveDevice(deviceId: string) {
    if (!confirm("Remove this device? It will need to be paired again to reconnect.")) {
      return;
    }
    await deleteDoc(doc(db, "devices", deviceId));
    if (selectedDeviceId === deviceId) {
      setSelectedDeviceId(null);
    }
  }

  async function handleGeneratePairingToken() {
    if (!user) return;
    setGeneratingToken(true);
    try {
      const token = Math.random().toString(36).slice(2, 10).toUpperCase();
      const expiresAt = new Date(Date.now() + 10 * 60 * 1000);

      await addDoc(collection(db, "pairingTokens"), {
        ownerId: user.uid,
        token,
        used: false,
        createdAt: serverTimestamp(),
        expiresAt,
      });

      setPairingToken(token);
    } finally {
      setGeneratingToken(false);
    }
  }

  async function handleStartListening() {
    if (!user || !selectedDeviceId) return;
    setRequestingSession(true);
    try {
      await addDoc(collection(db, "sessions"), {
        ownerId: user.uid,
        deviceId: selectedDeviceId,
        state: "requested",
        requestedAt: serverTimestamp(),
      });
    } finally {
      setRequestingSession(false);
    }
  }

  async function handleStopListening() {
    if (!activeSession) return;
    const { updateDoc } = await import("firebase/firestore");
    await updateDoc(doc(db, "sessions", activeSession.id), {
      state: "ended",
      endedAt: serverTimestamp(),
    });
  }

  return (
    <main className="flex-1 w-full max-w-2xl mx-auto p-6 flex flex-col gap-6">
      <header className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">Devices</h1>
        <button
          onClick={() => signOut(auth)}
          className="text-sm underline text-zinc-500"
        >
          Sign out
        </button>
      </header>

      <section className="border rounded p-4 flex flex-col gap-3">
        <h2 className="font-medium text-sm">Pair a new device</h2>
        <p className="text-sm text-zinc-500">
          Generate a one-time code, then enter it in the app on the phone you
          want to pair. The code expires in 10 minutes.
        </p>
        <button
          onClick={handleGeneratePairingToken}
          disabled={generatingToken}
          className="self-start rounded bg-black text-white px-4 py-2 text-sm disabled:opacity-50"
        >
          {generatingToken ? "Generating…" : "Generate pairing code"}
        </button>
        {pairingToken && (
          <p className="text-lg font-mono tracking-widest text-center py-2 bg-zinc-100 rounded dark:bg-zinc-900">
            {pairingToken}
          </p>
        )}
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="font-medium text-sm">Your devices</h2>
        {devices.length === 0 && (
          <p className="text-sm text-zinc-500">
            No devices paired yet. Generate a pairing code above to add one.
          </p>
        )}
        {devices.map((device) => (
          <div
            key={device.id}
            className={`border rounded p-4 flex items-center justify-between ${
              selectedDeviceId === device.id ? "border-black dark:border-white" : ""
            }`}
          >
            <button
              onClick={() => setSelectedDeviceId(device.id)}
              className="flex flex-col items-start text-left"
            >
              <span className="font-medium">{device.displayName || "Unnamed device"}</span>
              <span className="text-xs text-zinc-500">
                {device.status === "listening"
                  ? "● Listening"
                  : device.status === "online"
                  ? "● Online"
                  : "○ Offline"}
              </span>
            </button>
            <button
              onClick={() => handleRemoveDevice(device.id)}
              className="text-sm text-red-600 underline"
            >
              Remove
            </button>
          </div>
        ))}
      </section>

      {selectedDeviceId && (
        <section className="border rounded p-4 flex flex-col gap-3">
          <h2 className="font-medium text-sm">
            Session control — {devices.find((d) => d.id === selectedDeviceId)?.displayName}
          </h2>

          {!activeSession && (
            <>
              <p className="text-sm text-zinc-500">
                Request a live listening session with this device.
              </p>
              <button
                onClick={handleStartListening}
                disabled={requestingSession}
                className="self-start rounded bg-black text-white px-4 py-2 text-sm disabled:opacity-50"
              >
                {requestingSession ? "Requesting…" : "Start listening"}
              </button>
            </>
          )}

          {activeSession && (
            <>
              <p className="text-sm text-zinc-500">
                Session state: <span className="font-mono">{activeSession.state}</span>
                {activeSession.state === "requested" &&
                  " — waiting for the device to respond…"}
                {activeSession.state === "active" &&
                  " — audio transport not yet implemented (coming next)."}
              </p>
              <button
                onClick={handleStopListening}
                className="self-start rounded bg-red-600 text-white px-4 py-2 text-sm"
              >
                Stop listening
              </button>
            </>
          )}
        </section>
      )}
    </main>
  );
}

export default function DashboardPage() {
  return (
    <ProtectedRoute>
      <DashboardContent />
    </ProtectedRoute>
  );
}
