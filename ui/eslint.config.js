import js from "@eslint/js";
import globals from "globals";
import tseslint from "typescript-eslint";
import reactHooks from "eslint-plugin-react-hooks";
import react from "eslint-plugin-react";

export default tseslint.config(
  { ignores: ["dist/**", "node_modules/**"] },
  js.configs.recommended,
  {
    // Type-aware linting is scoped to the TypeScript sources. Applying it to every
    // file would fail on this config file, which is deliberately outside the
    // tsconfig project.
    files: ["**/*.{ts,tsx}"],
    extends: [...tseslint.configs.recommendedTypeChecked],
    languageOptions: {
      globals: globals.browser,
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    plugins: { "react-hooks": reactHooks, react },
    settings: {
      // `detect` reads the installed React version rather than restating it. An
      // explicit literal here would go stale the first time the `^18.3.1` range
      // in package.json resolves to a new minor.
      react: { version: "detect" },
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      "@typescript-eslint/consistent-type-imports": "error",
      "@typescript-eslint/no-unnecessary-condition": "error",

      // eslint-plugin-react. The set below is deliberately NOT
      // `react.configs.flat.recommended`: it is the set of rules SonarQube's own
      // TypeScript analyser runs, each annotated with the Sonar rule key it
      // backs, so that a finding the `sonar-ratchet-ui` CI job would gate is
      // visible to `npm run verify` first. Two consequences of that principle
      // are worth stating, because they are the reason for an explicit list:
      //
      //   - `react/jsx-child-element-spacing` (S6772) is off by default and is
      //     absent from `recommended`, so the preset would not have caught the
      //     three findings EOP-191 had to fix after CI reported them.
      //   - `react/react-in-jsx-scope` backs no Sonar key and is wrong under the
      //     automatic JSX runtime this project uses; it is excluded by the
      //     principle rather than by a special case.
      //
      // `react/prop-types` (S6774) is the one rule of Sonar's set omitted on
      // purpose: it demands runtime PropTypes declarations, which are redundant
      // when every prop shape is already a checked TypeScript interface, and it
      // would fire on every component. `default-props-match-prop-types` is kept
      // even so, because it only validates PropTypes that already exist and is
      // therefore inert here rather than noisy.
      //
      // Rules covering class components cannot fire in this hooks-only codebase.
      // They are enabled anyway: inert today, and load-bearing on the day a
      // class component appears.
      "react/button-has-type": "error", // S9011
      "react/default-props-match-prop-types": "error", // S6775
      "react/hook-use-state": "error", // S6754
      "react/jsx-child-element-spacing": "error", // S6772
      "react/jsx-key": "error", // S6477
      // S6480, with arrow functions allowed. The plugin's default reports 33
      // inline-arrow props across seven components; the ratchet reports zero
      // S6480 on the same tree, so the default would surface 33 findings no
      // gate tracks and force an unrelated refactor. Allowing arrows keeps
      // local parity with the ratchet exactly (both zero) while still catching
      // `.bind()` and `function`-expression props, which nothing else here
      // catches. Tightening to the default is its own story.
      "react/jsx-no-bind": ["error", { allowArrowFunctions: true }],
      "react/jsx-no-comment-textnodes": "error", // S6438
      "react/jsx-no-constructed-context-values": "error", // S6481
      "react/jsx-no-useless-fragment": "error", // S6749
      "react/jsx-pascal-case": "error", // S6770
      "react/no-access-state-in-setstate": "error", // S6756
      "react/no-array-index-key": "error", // S6479
      "react/no-children-prop": "error", // S6748
      "react/no-danger-with-children": "error", // S6761
      "react/no-deprecated": "error", // S6957
      "react/no-direct-mutation-state": "error", // S6746
      "react/no-find-dom-node": "error", // S6788
      "react/no-is-mounted": "error", // S6789
      "react/no-redundant-should-component-update": "error", // S6763
      "react/no-render-return-value": "error", // S6750
      "react/no-string-refs": "error", // S6790
      "react/no-this-in-sfc": "error", // S6757
      "react/no-unescaped-entities": "error", // S6766
      "react/no-unknown-property": "error", // S6747
      "react/no-unsafe": "error", // S6791
      "react/no-unstable-nested-components": "error", // S6478
      "react/no-unused-class-component-methods": "error", // S6441
      "react/no-unused-prop-types": "error", // S6767
      "react/require-render-return": "error", // S6435
    },
  },
  {
    // Build-time files that run under Node rather than in a browser: this config
    // itself and the Design System asset copier. They need Node globals
    // (`console`, `process`) and must not be type-checked, because they sit
    // outside the tsconfig project on purpose.
    files: ["**/*.js", "**/*.mjs"],
    extends: [tseslint.configs.disableTypeChecked],
    languageOptions: {
      globals: globals.node,
    },
  },
);
