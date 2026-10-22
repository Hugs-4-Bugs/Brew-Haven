/**
 * Server-side preference sync for signed-in customers.
 *
 * Anonymous visitors: localStorage only (guest/preferenceStore).
 * Signed-in customers: localStorage AND their account record — the account
 * copy follows them across devices. Conflict policy is deliberately simple
 * and honest: whichever record EXISTS wins when the other is missing; after
 * that the device record is the working copy and every save syncs upward.
 */

import { getToken } from '../api/client';
import { getServerPreferences, saveServerPreferences } from '../api/revenue';
import { loadGuestPrefRecord, savePreferences } from './preferenceStore';
import type { GuestPreferences } from './types';

/** On sign-in: if the device has no local record but the account does, adopt it. */
export async function hydratePreferencesFromServer(): Promise<void> {
  if (!getToken()) return;
  const local = loadGuestPrefRecord();
  if (local?.status === 'saved') return; // device record exists — keep it
  const server = await getServerPreferences();
  if (server?.preferences && typeof server.preferences === 'object') {
    savePreferences(server.preferences as GuestPreferences);
  }
}

/** Push the current saved preferences (if any) to the account record. */
export async function syncPreferencesToServer(): Promise<void> {
  if (!getToken()) return;
  const local = loadGuestPrefRecord();
  if (local?.status !== 'saved' || !local.preferences) return;
  await saveServerPreferences(JSON.stringify(local.preferences), local.version);
}
