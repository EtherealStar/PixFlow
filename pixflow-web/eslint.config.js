import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'
import tseslint from 'typescript-eslint'

function warningsAsErrors(configs) {
  return configs.map((config) => ({
    ...config,
    rules: Object.fromEntries(
      Object.entries(config.rules ?? {}).map(([rule, setting]) => {
        if (setting === 'warn' || setting === 1) {
          return [rule, 'error']
        }
        if (Array.isArray(setting) && (setting[0] === 'warn' || setting[0] === 1)) {
          return [rule, ['error', ...setting.slice(1)]]
        }
        return [rule, setting]
      }),
    ),
  }))
}

export default tseslint.config(
  {
    ignores: ['coverage/**', 'dist/**', 'node_modules/**'],
  },
  {
    files: ['src/**/*.{js,mjs,cjs}'],
    ...js.configs.recommended,
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.node,
      },
    },
  },
  {
    files: ['src/**/*.{ts,tsx,vue}'],
    extends: [...tseslint.configs.recommendedTypeChecked],
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.node,
      },
      parserOptions: {
        extraFileExtensions: ['.vue'],
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      '@typescript-eslint/no-floating-promises': 'error',
      '@typescript-eslint/no-unused-vars': [
        'error',
        {
          argsIgnorePattern: '^_',
          caughtErrorsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
        },
      ],
      eqeqeq: ['error', 'always'],
      'prefer-const': 'error',
    },
  },
  ...warningsAsErrors(pluginVue.configs['flat/recommended']),
  {
    files: ['src/**/*.vue'],
    languageOptions: {
      parserOptions: {
        extraFileExtensions: ['.vue'],
        parser: tseslint.parser,
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
  },
  {
    files: ['src/**/__tests__/**/*.{ts,tsx}', 'src/**/*.{spec,test}.{ts,tsx}'],
    languageOptions: {
      globals: globals.vitest,
    },
  },
)
