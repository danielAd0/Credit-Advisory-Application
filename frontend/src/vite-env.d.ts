/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_USE_MOCK?: string;
  readonly VITE_MOCK_ROLE?: string;
  /** Mock mode: "true" makes the mock ask for the two-step code like the real server. */
  readonly VITE_MOCK_MFA?: string;
}
interface ImportMeta {
  readonly env: ImportMetaEnv;
}
