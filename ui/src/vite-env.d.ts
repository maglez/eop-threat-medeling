/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_GAME_SCREEN_ENABLED: string;
  readonly VITE_CARD_MAGNIFIER_ENABLED: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}