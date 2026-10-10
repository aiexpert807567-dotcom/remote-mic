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
} from "firebase/firestore";
import { signOut } from "firebase/auth";
import { db, auth } from "@/lib/firebase";
import { useAuth } from "@/lib/auth-context";
import ProtectedRoute from "@/components/ProtectedRoute";

interface DeviceDoc {
  id: string;
  displayName: string;
  status: "online" | "offline" | "unknown";
  pairedAt?: { seconds: number };
  lastSeenAt?: { seconds: number };
}

function DashboardContent() {
  const { user } = useAuth();
  const [devices, setDevices] = useState<DeviceDoc[]>([]);
  const [selectedDeviceId, setSelectedDeviceId] = useState<string | null>(null);
  const [pairingToken, setPairingToken] = useState<string | null>(null);
  const [generatingToken, setGeneratingToken] = useState(false);

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
                {device.status === "online" ? "● Online" : "○ Offline"}
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
          <p className="text-sm text-zinc-500">
            Live listening controls will be wired up once WebRTC signaling is in place.
          </p>
          <button
            disabled
            className="self-start rounded bg-zinc-300 text-zinc-600 px-4 py-2 text-sm cursor-not-allowed dark:bg-zinc-800"
          >
            Start listening (coming soon)
          </button>
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
