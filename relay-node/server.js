const http = require("http");
const { WebSocketServer, WebSocket } = require("ws");

const PORT = process.env.PORT || 8080;
const rooms = new Map();

const server = http.createServer((req, res) => {
  if (req.url === "/health" || req.url === "/") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: true, service: "netsetu-relay", activeRooms: rooms.size }));
    return;
  }
  res.writeHead(404);
  res.end("Not found");
});

const wss = new WebSocketServer({ server, path: "/ws" });

function sendStatus(ws, obj) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

wss.on("connection", (ws, req) => {
  const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
  const role = (url.searchParams.get("role") || "").toLowerCase();
  const room = (url.searchParams.get("room") || "").trim();
  const pin = (url.searchParams.get("pin") || "").trim();

  if (!room || room.length > 128 || (role !== "host" && role !== "friend")) {
    ws.close(1008, "Invalid role or room");
    return;
  }

  let entry = rooms.get(room);
  if (!entry) {
    entry = { host: null, friend: null, pin };
    rooms.set(room, entry);
  }

  if (role === "host") {
    if (entry.host && entry.host.readyState === WebSocket.OPEN) entry.host.close(1000, "Replaced by new host");
    entry.host = ws;
    entry.pin = pin;
    sendStatus(ws, { type: "status", connected: true, peerOnline: Boolean(entry.friend && entry.friend.readyState === WebSocket.OPEN) });
    if (entry.friend) sendStatus(entry.friend, { type: "status", connected: true, peerOnline: true });
  } else {
    if (!entry.host || entry.host.readyState !== WebSocket.OPEN) {
      sendStatus(ws, { type: "error", message: "Host is offline for this room" });
      ws.close(1008, "Host offline");
      return;
    }
    if (entry.pin && entry.pin !== pin) {
      sendStatus(ws, { type: "error", message: "Incorrect PIN" });
      ws.close(1008, "Wrong PIN");
      return;
    }
    if (entry.friend && entry.friend.readyState === WebSocket.OPEN) entry.friend.close(1000, "Replaced by new friend");
    entry.friend = ws;
    sendStatus(ws, { type: "status", connected: true, peerOnline: true });
    sendStatus(entry.host, { type: "status", connected: true, peerOnline: true });
  }

  ws.on("message", (data, isBinary) => {
    if (!isBinary) return;
    const peer = role === "host" ? entry.friend : entry.host;
    if (peer && peer.readyState === WebSocket.OPEN) peer.send(data, { binary: true });
  });

  ws.on("close", () => {
    const current = rooms.get(room);
    if (!current) return;
    if (role === "host" && current.host === ws) {
      current.host = null;
      if (current.friend) sendStatus(current.friend, { type: "status", connected: true, peerOnline: false });
    } else if (role === "friend" && current.friend === ws) {
      current.friend = null;
      if (current.host) sendStatus(current.host, { type: "status", connected: true, peerOnline: false });
    }
    if (!current.host && !current.friend) rooms.delete(room);
  });
});

server.listen(PORT, "0.0.0.0", () => console.log(`NetSetu Relay running on port ${PORT}`));
