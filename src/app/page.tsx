// Home page - Static Site Generation
import Hero from '@/components/sections/Hero';
import SkillsSection from '@/components/sections/SkillsSection';
import FeaturedProjects from '@/components/sections/FeaturedProjects';

export const metadata = {
  title: 'Home',
  description:
    'Portfolio profissional de Orlando Fernandes. React, Next.js, TypeScript e muito mais.',
};

export default function HomePage() {
  return (
    <>
      <Hero
        name="Orlando Fernandes"
        title="Senior Software Engineer"
        subtitle="React | Next.js | TypeScript | 5+ anos de experiência"
      />
      <SkillsSection />
      <FeaturedProjects />
    </>
  );
}
