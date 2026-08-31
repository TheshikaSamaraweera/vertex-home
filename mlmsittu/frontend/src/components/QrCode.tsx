import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import QRCode from 'qrcode';

/**
 * A QR code, rendered client-side.
 *
 * <h2>Why client-side</h2>
 *
 * The value encoded here is a TOTP enrolment URI, which contains the shared secret. It is already
 * in the login response this component was handed; encoding it in the browser means it is not
 * additionally round-tripped to a server, written to an image cache, or logged by anything in
 * between. The backend deliberately owns no image library for the same reason.
 *
 * <h2>Why a library</h2>
 *
 * The rest of this project's UI primitives are hand-written to avoid dependencies. QR encoding is
 * the exception and should be: it is Reed–Solomon error correction, mask evaluation and bit
 * packing to a spec, and a subtly wrong implementation produces a code that scans on the phone you
 * tested with and fails on somebody else's.
 *
 * Rendered as a data URI rather than to a canvas so it prints, survives a screenshot, and needs no
 * ref juggling.
 */
export function QrCode({
  value,
  size = 208,
  label,
}: {
  value: string;
  size?: number;
  label?: string;
}) {
  const { t } = useTranslation();
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let live = true;
    QRCode.toDataURL(value, {
      width: size,
      margin: 1,
      // Medium correction. The enrolment URI is long enough that High would push the code into a
      // denser version, and a denser code is harder for an old phone camera to read — which is
      // exactly the device this needs to work on.
      errorCorrectionLevel: 'M',
      color: { dark: '#14221a', light: '#ffffff' },
    })
      .then((url) => {
        if (live) {
          setSrc(url);
          setFailed(false);
        }
      })
      .catch(() => {
        // Never leave a blank square. The setup key is always shown beside this, so a failed QR
        // is an inconvenience rather than a dead end — but only if the reader is told so.
        if (live) setFailed(true);
      });
    return () => {
      live = false;
    };
  }, [value, size]);

  if (failed) {
    return (
      <div
        className="flex items-center justify-center rounded-lg border border-rule bg-panel2 p-4 text-center text-xs text-ink3"
        style={{ width: size, height: size }}
      >
        {t('Could not draw the QR code. Type the setup key instead.')}
      </div>
    );
  }

  if (!src) {
    return (
      <div
        className="animate-pulse rounded-lg border border-rule bg-panel2"
        style={{ width: size, height: size }}
      />
    );
  }

  return (
    <img
      src={src}
      width={size}
      height={size}
      // The QR is decorative to a screen reader — it cannot scan it, and the setup key beside it
      // carries the same information in a form it can read aloud.
      alt={label ?? t('QR code for setting up your authenticator app')}
      className="rounded-lg border border-rule bg-white"
    />
  );
}
