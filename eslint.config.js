import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import firebaseRulesPlugin from '@firebase/eslint-plugin-security-rules';

export default [
  {
    ignores: ['dist/**/*', 'node_modules/**/*', 'app/**/*', 'gradle/**/*'],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  firebaseRulesPlugin.configs['flat/recommended'],
  {
    files: ['src/**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-unused-vars': 'off',
    },
  },
];
