import { useEffect } from 'react';
import { useRouter } from 'expo-router';
import Constants from 'expo-constants';
import { pushApi } from '@/services/pushApi';

/** A device callback is distinct from Expo's ticket/receipt and from user READ. */
export function PushReceiptListener({ actor }: { actor: string }) {
  const router = useRouter();
  useEffect(() => {
    if (!actor || Constants.appOwnership === 'expo') return;
    let active = true;
    let cleanup: (() => void) | undefined;
    try {
      // Expo Go lacks the Android remote-push module; its in-app inbox still works.
      // eslint-disable-next-line @typescript-eslint/no-require-imports
      const Notifications = require('expo-notifications') as typeof import('expo-notifications');
      if (!active) return;
      const delivered = Notifications.addNotificationReceivedListener(event => {
        const id = event.request.content.data?.notificationId;
        if (typeof id === 'string' && /^[0-9a-f-]{36}$/i.test(id))
          void pushApi.acknowledgeReceived(id).catch(() => {});
      });
      const opened = Notifications.addNotificationResponseReceivedListener(() => {
        // Never trust a payload route; the inbox rechecks current authority.
        router.push('/notifications' as never);
      });
      cleanup = () => { delivered.remove(); opened.remove(); };
    } catch { /* Native listener is unavailable; in-app inbox remains authoritative. */ }
    return () => { active = false; cleanup?.(); };
  }, [actor, router]);
  return null;
}
