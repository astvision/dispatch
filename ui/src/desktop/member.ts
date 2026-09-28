const KEY = "dispatch.member";
let remembered: string | null = null;

/** The admin this browser acts as on a team's desktop (D-2), once chosen; kept in memory too when storage is blocked. */
export function chosenMember(): string | null {
  try {
    return window.localStorage.getItem(KEY) ?? remembered;
  } catch {
    return remembered;
  }
}

export function chooseMember(ref: string | null) {
  remembered = ref;
  try {
    if (ref) window.localStorage.setItem(KEY, ref);
    else window.localStorage.removeItem(KEY);
  } catch {
    // Remembered for this visit only.
  }
}
