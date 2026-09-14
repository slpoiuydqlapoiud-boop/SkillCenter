export const LOGIN_MODES = Object.freeze({ developer: "developer", admin: "admin" });

export function requiresAdminCredentials(mode) {
  return mode === LOGIN_MODES.admin;
}

export function createDeveloperSession() {
  return { userId: "developer-user", role: "developer", displayName: "普通开发者" };
}

const SESSION_KEY = "skill-center.session";

export function readSession(storage = typeof window !== "undefined" ? window.localStorage : null) {
  if (!storage) return null;
  try {
    const value = storage.getItem(SESSION_KEY);
    return value ? JSON.parse(value) : null;
  } catch {
    return null;
  }
}

export function saveSession(session, storage = typeof window !== "undefined" ? window.localStorage : null) {
  if (storage) storage.setItem(SESSION_KEY, JSON.stringify(session));
  return session;
}

export function clearSession(storage = typeof window !== "undefined" ? window.localStorage : null) {
  if (storage) storage.removeItem(SESSION_KEY);
}
