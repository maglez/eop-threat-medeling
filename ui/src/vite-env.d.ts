/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_GAME_SCREEN_ENABLED: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}