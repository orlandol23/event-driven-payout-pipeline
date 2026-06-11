# Portfolio Profissional - Orlando Fernandes

![CI](https://github.com/orlandol23/portfolio/workflows/CI/badge.svg)
![Next.js](https://img.shields.io/badge/Next.js-14+-black)
![TypeScript](https://img.shields.io/badge/TypeScript-5.9-blue)
![License](https://img.shields.io/badge/license-MIT-green)

Portfolio profissional desenvolvido com Next.js 14+, TypeScript, Tailwind CSS e as melhores práticas de desenvolvimento web.

## 🚀 Tecnologias

### Core

- **Next.js 14+** - Framework React com App Router
- **React 18+** - Biblioteca UI
- **TypeScript** - Tipagem estática (strict mode)
- **Tailwind CSS** - Framework CSS utility-first

### Formulários e Validação

- **React Hook Form** - Gerenciamento de formulários
- **Zod** - Validação de schemas
- **DOMPurify** - Sanitização XSS

### Testes

- **Jest** - Framework de testes
- **React Testing Library** - Testes de componentes
- **Coverage > 70%** - Alta cobertura de testes

### Qualidade de Código

- **ESLint** - Linter JavaScript/TypeScript
- **Prettier** - Formatação de código
- **TypeScript Strict Mode** - Tipagem rigorosa

### DevOps

- **GitHub Actions** - CI/CD
- **Vercel** - Deploy e hosting

## 📋 Features

- ✅ **SSG (Static Site Generation)** - Home, About
- ✅ **ISR (Incremental Static Regeneration)** - Projects
- ✅ **SSR (Server-Side Rendering)** - Contact
- ✅ **API Routes** - Formulário de contato
- ✅ **Validação dupla** - Frontend + Backend
- ✅ **Segurança OWASP** - Headers, sanitização, validação
- ✅ **Design Responsivo** - Mobile-first
- ✅ **Acessibilidade** - ARIA labels, semântica HTML
- ✅ **Performance** - Otimização de imagens, code splitting
- ✅ **SEO** - Metadata, Open Graph

## 🏗️ Estrutura do Projeto

```
portfolio/
├── src/
│   ├── app/                    # App Router (Next.js 14+)
│   │   ├── page.tsx            # Home (SSG)
│   │   ├── about/              # About (SSG)
│   │   ├── projects/           # Projects (ISR)
│   │   ├── contact/            # Contact (SSR)
│   │   └── api/                # API Routes
│   ├── components/
│   │   ├── ui/                 # Componentes reutilizáveis
│   │   ├── layout/             # Header, Footer
│   │   └── sections/           # Hero, Skills, etc
│   ├── lib/                    # Utilitários
│   ├── types/                  # TypeScript types
│   └── styles/                 # CSS global
├── __tests__/                  # Testes
├── public/                     # Assets estáticos
└── .github/workflows/          # CI/CD
```

## 🚀 Começando

### Pré-requisitos

- Node.js 18+
- npm ou yarn

### Instalação

```bash
# Clonar repositório
git clone https://github.com/orlandol23/portfolio.git
cd portfolio

# Instalar dependências
npm install

# Rodar servidor de desenvolvimento
npm run dev
```

Abra [http://localhost:3000](http://localhost:3000) no navegador.

## 📝 Scripts Disponíveis

```bash
npm run dev          # Servidor de desenvolvimento
npm run build        # Build de produção
npm run start        # Rodar build de produção
npm run lint         # Verificar código com ESLint
npm run test         # Rodar testes
npm run test:watch   # Testes em modo watch
npm run test:coverage # Coverage de testes
npm run format:check  # Verificar formatação
npm run format:write  # Formatar código
```

## 🧪 Testes

```bash
# Rodar todos os testes
npm test

# Rodar com coverage
npm run test:coverage

# Modo watch (desenvolvimento)
npm run test:watch
```

### Coverage atual

- **Branches:** 70%+
- **Functions:** 70%+
- **Lines:** 70%+
- **Statements:** 70%+

## 🔒 Segurança

### Headers de Segurança

- `X-Frame-Options: DENY` - Previne clickjacking
- `X-Content-Type-Options: nosniff` - Previne MIME sniffing
- `X-XSS-Protection: 1; mode=block` - Proteção XSS
- `Content-Security-Policy` - CSP configurado
- `Referrer-Policy` - Controle de referrer

### Validação e Sanitização

- Validação dupla (frontend + backend) com Zod
- Sanitização de inputs com DOMPurify
- Rate limiting em API routes (recomendado para produção)

## 📦 Build e Deploy

### Build local

```bash
npm run build
npm run start
```

### Deploy na Vercel

O projeto está configurado para deploy automático na Vercel:

1. Conecte seu repositório GitHub à Vercel
2. A cada push para `main`, deploy automático é executado
3. PRs recebem preview deployments

## 🎨 Customização

### Cores (Tailwind)

Edite `tailwind.config.ts` para customizar o tema:

```typescript
theme: {
  extend: {
    colors: {
      primary: {
        // Suas cores aqui
      }
    }
  }
}
```

### Conteúdo

- **Informações pessoais:** `src/components/layout/Header.tsx`, `Footer.tsx`
- **Projetos:** `src/lib/api.ts` - substitua pelo seu GitHub ou API
- **Skills:** `src/components/sections/SkillsSection.tsx`

## 📄 Licença

Este projeto está sob a licença MIT. Veja o arquivo [LICENSE](LICENSE) para mais detalhes.

## 🤝 Contato

Orlando Fernandes

- Email: orlando@example.com
- LinkedIn: [/in/orlando-fernandes](https://linkedin.com/in/orlando-fernandes)
- GitHub: [@orlandol23](https://github.com/orlandol23)

---

**Desenvolvido com ❤️ usando Next.js 14+ e TypeScript**
