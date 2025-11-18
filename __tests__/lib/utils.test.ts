// Testes unitários para funções utilitárias
import { cn, formatDate, slugify } from '@/lib/utils';

describe('Utils', () => {
  describe('cn', () => {
    it('should combine multiple class names', () => {
      const result = cn('class1', 'class2', 'class3');
      expect(result).toBe('class1 class2 class3');
    });

    it('should filter out falsy values', () => {
      const result = cn('class1', undefined, 'class2', null, false, 'class3');
      expect(result).toBe('class1 class2 class3');
    });

    it('should handle empty input', () => {
      const result = cn();
      expect(result).toBe('');
    });

    it('should handle all falsy values', () => {
      const result = cn(undefined, null, false);
      expect(result).toBe('');
    });
  });

  describe('formatDate', () => {
    it('should format date string to pt-BR locale', () => {
      const result = formatDate('2024-01-15');
      expect(result).toContain('janeiro');
      expect(result).toContain('2024');
    });

    it('should format Date object to pt-BR locale', () => {
      const date = new Date('2024-01-15');
      const result = formatDate(date);
      expect(result).toContain('janeiro');
      expect(result).toContain('2024');
    });

    it('should include day, month, and year', () => {
      const result = formatDate('2024-03-20');
      expect(result).toContain('20');
      expect(result).toContain('março');
      expect(result).toContain('2024');
    });
  });

  describe('slugify', () => {
    it('should convert text to lowercase', () => {
      const result = slugify('Hello World');
      expect(result).toBe('hello-world');
    });

    it('should replace spaces with hyphens', () => {
      const result = slugify('This is a test');
      expect(result).toBe('this-is-a-test');
    });

    it('should remove special characters', () => {
      const result = slugify('Hello! World? Test@123');
      expect(result).toBe('hello-world-test123');
    });

    it('should remove accents', () => {
      const result = slugify('José María');
      expect(result).toBe('jose-maria');
    });

    it('should handle multiple consecutive spaces', () => {
      const result = slugify('Hello    World');
      expect(result).toBe('hello-world');
    });

    it('should handle multiple consecutive hyphens', () => {
      const result = slugify('Hello---World');
      expect(result).toBe('hello-world');
    });

    it('should trim leading and trailing spaces', () => {
      const result = slugify('  Hello World  ');
      expect(result).toBe('hello-world');
    });

    it('should handle empty string', () => {
      const result = slugify('');
      expect(result).toBe('');
    });

    it('should handle complex text with accents and special chars', () => {
      const result = slugify('Olá! Mundo? Café & Açúcar');
      expect(result).toBe('ola-mundo-cafe-acucar');
    });
  });
});
