/**
 * Active-campaign banner for the guest surface (signed-in customers).
 *
 * Shows ONLY campaigns the backend marked ACTIVE and server-validates at
 * checkout. Impression (VIEWED) is reported once per campaign; clicking
 * reports CLICKED. Anonymous visitors see nothing (campaign API requires an
 * account) — the banner silently hides rather than erroring.
 */

import { useEffect, useRef, useState } from 'react';
import { getActiveCampaigns, reportCampaignInteraction, type ActiveCampaign } from '../../api/revenue';

interface CampaignBannerProps {
  /** Applied code is lifted to the page state so checkout can pre-fill it. */
  onApply?: (campaign: ActiveCampaign) => void;
}

export default function CampaignBanner({ onApply }: CampaignBannerProps): JSX.Element | null {
  const [campaigns, setCampaigns] = useState<ActiveCampaign[]>([]);
  const reportedView = useRef<Set<number>>(new Set());

  useEffect(() => {
    let cancelled = false;
    getActiveCampaigns().then((list) => {
      if (!cancelled) setCampaigns(list);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  if (campaigns.length === 0) return null;
  const campaign = campaigns[0];
  if (!reportedView.current.has(campaign.id)) {
    reportedView.current.add(campaign.id);
    void reportCampaignInteraction(campaign.id, 'VIEWED');
  }

  const offerLabel =
    campaign.offerType === 'PERCENT' && campaign.offerValue
      ? `${campaign.offerValue}% off`
      : campaign.offerType === 'FLAT' && campaign.offerValue
        ? `₹${campaign.offerValue} off`
        : 'a special offer';

  return (
    <aside className="campaign-banner" role="region" aria-label="Current offer">
      <span className="campaign-emoji" aria-hidden="true">🎉</span>
      <span className="campaign-text">
        <strong>{campaign.name}</strong> — {offerLabel}. Apply code <code>{campaign.code}</code> at checkout.
      </span>
      <button
        type="button"
        className="btn btn-primary campaign-apply"
        onClick={() => {
          void reportCampaignInteraction(campaign.id, 'CLICKED');
          onApply?.(campaign);
        }}
      >
        Apply offer
      </button>
    </aside>
  );
}
