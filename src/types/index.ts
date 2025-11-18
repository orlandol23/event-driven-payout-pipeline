// TypeScript interfaces centralizadas

export interface Project {
  id: string;
  slug: string;
  name: string;
  description: string;
  image?: string;
  tags: string[];
  githubUrl?: string;
  demoUrl?: string;
  stargazers_count?: number;
  language?: string;
  html_url?: string;
  homepage?: string;
}

export interface BlogPost {
  id: string;
  slug: string;
  title: string;
  excerpt: string;
  content: string;
  publishedAt: string;
  category: string;
  tags: string[];
}

export interface ContactFormData {
  name: string;
  email: string;
  message: string;
}

export interface NavLink {
  href: string;
  label: string;
}
