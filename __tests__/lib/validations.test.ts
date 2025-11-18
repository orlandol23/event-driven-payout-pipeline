// Testes unitários para schemas Zod
import { contactSchema } from '@/lib/validations';

describe('contactSchema', () => {
  describe('name validation', () => {
    it('should accept valid name', () => {
      const result = contactSchema.safeParse({
        name: 'Orlando Fernandes',
        email: 'orlando@example.com',
        message: 'Test message with enough characters',
      });

      expect(result.success).toBe(true);
    });

    it('should reject name with less than 2 characters', () => {
      const result = contactSchema.safeParse({
        name: 'O',
        email: 'orlando@example.com',
        message: 'Test message with enough characters',
      });

      expect(result.success).toBe(false);
      if (!result.success) {
        expect(result.error.issues[0].message).toBe('Nome deve ter no mínimo 2 caracteres');
      }
    });

    it('should reject name with more than 100 characters', () => {
      const result = contactSchema.safeParse({
        name: 'a'.repeat(101),
        email: 'orlando@example.com',
        message: 'Test message with enough characters',
      });

      expect(result.success).toBe(false);
      if (!result.success) {
        expect(result.error.issues[0].message).toBe('Nome deve ter no máximo 100 caracteres');
      }
    });

    it('should reject name with numbers', () => {
      const result = contactSchema.safeParse({
        name: 'Orlando123',
        email: 'orlando@example.com',
        message: 'Test message with enough characters',
      });

      expect(result.success).toBe(false);
      if (!result.success) {
        expect(result.error.issues[0].message).toBe('Nome deve conter apenas letras');
      }
    });

    it('should accept name with accents', () => {
      const result = contactSchema.safeParse({
        name: 'José María',
        email: 'jose@example.com',
        message: 'Test message with enough characters',
      });

      expect(result.success).toBe(true);
    });
  });

  describe('email validation', () => {
    it('should accept valid email', () => {
      const result = contactSchema.safeParse({
        name: 'Orlando Fernandes',
        email: 'orlando@example.com',
        message: 'Test message with enough characters',
      });

      expect(result.success).toBe(true);
    });

    it('should reject invalid email', () => {
      const result = contactSchema.safeParse({
        name: 'Orlando Fernandes',
        email: 'invalid-email',
        message: 'Test message with enough characters',
      });

      expect(result.success).toBe(false);
      if (!result.success) {
        expect(result.error.issues[0].message).toBe('Email inválido');
      }
    });

    it('should reject email with more than 255 characters', () => {
      const longEmail = 'a'.repeat(250) + '@test.com';
      const result = contactSchema.safeParse({
        name: 'Orlando Fernandes',
        email: longEmail,
        message: 'Test message with enough characters',
      });

      expect(result.success).toBe(false);
    });
  });

  describe('message validation', () => {
    it('should accept valid message', () => {
      const result = contactSchema.safeParse({
        name: 'Orlando Fernandes',
        email: 'orlando@example.com',
        message: 'This is a valid message with enough characters',
      });

      expect(result.success).toBe(true);
    });

    it('should reject message with less than 10 characters', () => {
      const result = contactSchema.safeParse({
        name: 'Orlando Fernandes',
        email: 'orlando@example.com',
        message: 'Short',
      });

      expect(result.success).toBe(false);
      if (!result.success) {
        expect(result.error.issues[0].message).toBe('Mensagem deve ter no mínimo 10 caracteres');
      }
    });

    it('should reject message with more than 1000 characters', () => {
      const result = contactSchema.safeParse({
        name: 'Orlando Fernandes',
        email: 'orlando@example.com',
        message: 'a'.repeat(1001),
      });

      expect(result.success).toBe(false);
      if (!result.success) {
        expect(result.error.issues[0].message).toBe(
          'Mensagem deve ter no máximo 1000 caracteres'
        );
      }
    });
  });

  describe('complete validation', () => {
    it('should reject when all fields are invalid', () => {
      const result = contactSchema.safeParse({
        name: 'O',
        email: 'invalid',
        message: 'Short',
      });

      expect(result.success).toBe(false);
      if (!result.success) {
        expect(result.error.issues.length).toBeGreaterThan(0);
      }
    });

    it('should accept when all fields are valid', () => {
      const result = contactSchema.safeParse({
        name: 'Orlando Fernandes',
        email: 'orlando@example.com',
        message: 'This is a completely valid message with all the required characters.',
      });

      expect(result.success).toBe(true);
    });
  });
});
