// API functions para buscar dados
import { Project } from '@/types';

// Mock data para projetos - em produção, buscar do GitHub API
export async function getProjects(): Promise<Project[]> {
  // Simula busca de projetos (pode ser substituído por fetch real do GitHub)
  const mockProjects: Project[] = [
    {
      id: '1',
      slug: 'ecommerce',
      name: 'E-commerce Platform',
      description:
        'Plataforma de e-commerce completa com Next.js, TypeScript, Stripe para pagamentos e PostgreSQL. Inclui autenticação, carrinho de compras, checkout e painel administrativo.',
      tags: ['Next.js', 'TypeScript', 'Stripe', 'PostgreSQL', 'Tailwind CSS'],
      githubUrl: 'https://github.com/orlandol23/ecommerce',
      demoUrl: 'https://ecommerce-demo.vercel.app',
      stargazers_count: 42,
    },
    {
      id: '2',
      slug: 'task-manager',
      name: 'Task Management App',
      description:
        'Aplicação de gerenciamento de tarefas com drag-and-drop, autenticação JWT, real-time updates via WebSockets e MongoDB para persistência.',
      tags: ['React', 'Node.js', 'WebSockets', 'MongoDB', 'Express'],
      githubUrl: 'https://github.com/orlandol23/task-manager',
      demoUrl: 'https://task-manager-demo.vercel.app',
      stargazers_count: 28,
    },
    {
      id: '3',
      slug: 'blog',
      name: 'Blog Platform',
      description:
        'Plataforma de blog com CMS headless (Contentful), suporte a Markdown/MDX, otimização de SEO, geração estática de páginas e sitemap automático.',
      tags: ['Next.js', 'Contentful', 'MDX', 'SEO', 'ISR'],
      githubUrl: 'https://github.com/orlandol23/blog',
      demoUrl: 'https://blog-demo.vercel.app',
      stargazers_count: 35,
    },
    {
      id: '4',
      slug: 'dashboard',
      name: 'Analytics Dashboard',
      description:
        'Dashboard analítico com visualizações de dados interativas usando Chart.js, filtros dinâmicos e exportação de relatórios em PDF.',
      tags: ['React', 'Chart.js', 'TypeScript', 'Redux', 'Material-UI'],
      githubUrl: 'https://github.com/orlandol23/dashboard',
      demoUrl: 'https://dashboard-demo.vercel.app',
      stargazers_count: 19,
    },
    {
      id: '5',
      slug: 'api-gateway',
      name: 'API Gateway',
      description:
        'Gateway de API com rate limiting, autenticação, cache, logging e monitoramento. Construído com Node.js e Redis.',
      tags: ['Node.js', 'Express', 'Redis', 'JWT', 'Docker'],
      githubUrl: 'https://github.com/orlandol23/api-gateway',
      stargazers_count: 51,
    },
    {
      id: '6',
      slug: 'chat-app',
      name: 'Real-time Chat',
      description:
        'Aplicação de chat em tempo real com suporte a mensagens privadas, grupos, notificações push e upload de arquivos.',
      tags: ['React', 'Socket.io', 'Node.js', 'MongoDB', 'AWS S3'],
      githubUrl: 'https://github.com/orlandol23/chat-app',
      demoUrl: 'https://chat-demo.vercel.app',
      stargazers_count: 64,
    },
  ];

  // Simula delay de rede
  await new Promise(resolve => setTimeout(resolve, 100));

  return mockProjects;
}

export async function getProjectBySlug(slug: string): Promise<Project | null> {
  const projects = await getProjects();
  return projects.find(p => p.slug === slug) || null;
}

// Função alternativa para buscar do GitHub API real (comentada)
/*
export async function getGitHubProjects(username: string): Promise<Project[]> {
  try {
    const res = await fetch(`https://api.github.com/users/${username}/repos`, {
      next: { revalidate: 3600 }, // ISR: revalida a cada 1 hora
    });

    if (!res.ok) {
      throw new Error('Failed to fetch projects');
    }

    const repos = await res.json();

    return repos.map((repo: any) => ({
      id: repo.id.toString(),
      slug: repo.name,
      name: repo.name,
      description: repo.description || 'Sem descrição',
      tags: [repo.language].filter(Boolean),
      githubUrl: repo.html_url,
      demoUrl: repo.homepage || undefined,
      stargazers_count: repo.stargazers_count,
    }));
  } catch (error) {
    console.error('Error fetching GitHub projects:', error);
    return [];
  }
}
*/
