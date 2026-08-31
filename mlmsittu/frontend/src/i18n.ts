import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';

/**
 * i18n scaffolding from day one (architecture 6.5) — the risk register lists "Sinhala retrofit" as
 * rework to avoid, and retrofitting means touching every string in the app.
 *
 * **Keys are the English text itself.** `t('Save changes')` rather than `t('common.saveChanges')`.
 * That means:
 *
 * - English needs no bundle at all — the key is the translation, so nothing can drift between a
 *   key and the label it is supposed to render.
 * - Adding Sinhala later is purely additive: drop in `si.json` keyed by the English strings.
 * - Every user-facing string is already wrapped, which is the part that is expensive to add later.
 *
 * Sinhala also needs Noto Sans Sinhala and roughly 30% more width in layouts (6.5). Buttons and
 * table headers here are sized by content rather than fixed widths for that reason.
 */
// Not awaited: the English resources are inline, so init resolves synchronously and awaiting it
// would force top-level await into the bundle for no benefit.
void i18next.use(initReactI18next).init({
  lng: 'en',
  fallbackLng: 'en',
  // Natural-language keys contain dots, colons and spaces; the separators must be off or
  // "Stock: on hand" would be parsed as a namespace path.
  keySeparator: false,
  nsSeparator: false,
  resources: {
    en: { translation: {} },
  },
  interpolation: { escapeValue: false },
});

export default i18next;
