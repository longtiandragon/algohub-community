const activeDrafts = new Set<() => void>()

export function registerOnboardingDraftCleanup(clear: () => void) {
  activeDrafts.add(clear)
  return () => activeDrafts.delete(clear)
}

/** Registration drafts contain personal data and must not outlive an identity change. */
export function clearOnboardingDrafts() {
  for (const clear of activeDrafts) clear()
  try {
    for (let index = sessionStorage.length - 1; index >= 0; index--) {
      const key = sessionStorage.key(index)
      if (key?.startsWith('algohub-onboarding-')) sessionStorage.removeItem(key)
    }
  } catch { /* Some private browsing modes disable storage. */ }
}
