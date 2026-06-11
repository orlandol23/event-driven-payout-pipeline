// ESLint 9 flat config.
// eslint-config-next v16 exports native flat configs, so we import
// "next/core-web-vitals" directly instead of going through FlatCompat
// (which only supports legacy eslintrc-style shareable configs).
import coreWebVitals from 'eslint-config-next/core-web-vitals';

const eslintConfig = [
  {
    ignores: ['node_modules/**', '.next/**', 'out/**', 'coverage/**', 'next-env.d.ts'],
  },
  ...coreWebVitals,
  {
    rules: {
      'no-console': 'warn',
      'prefer-const': 'error',
    },
  },
];

export default eslintConfig;
