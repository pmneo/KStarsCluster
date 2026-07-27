/**
 * Connects to a same-origin WebSocket path with auto-reconnect and a 1s
 * keep-alive ping, mirroring the protocol the backend's LoggingSocket/
 * StatusSocket endpoints already speak (see the old Angular LogWebSocket).
 * Returns a cleanup function.
 */
export function connectSocket(
  path: string,
  onMessage: (data: string) => void,
  onStatusChange?: (connected: boolean) => void,
): () => void {
  let ws: WebSocket | null = null;
  let keepAlive: ReturnType<typeof setInterval> | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let closed = false;

  function connect() {
    if (closed) return;

    const proto = window.location.protocol.replace('http', 'ws');
    ws = new WebSocket(`${proto}//${window.location.host}${path}`);

    ws.onopen = () => {
      onStatusChange?.(true);
      keepAlive = setInterval(() => ws?.send('KEEP_ALIVE'), 1000);
    };
    ws.onmessage = (m) => {
      if (m.data) onMessage(m.data);
    };
    ws.onclose = () => {
      onStatusChange?.(false);
      if (keepAlive) clearInterval(keepAlive);
      if (!closed) reconnectTimer = setTimeout(connect, 1000);
    };
    ws.onerror = () => {
      ws?.close();
    };
  }

  connect();

  return () => {
    closed = true;
    if (keepAlive) clearInterval(keepAlive);
    if (reconnectTimer) clearTimeout(reconnectTimer);
    ws?.close();
  };
}
