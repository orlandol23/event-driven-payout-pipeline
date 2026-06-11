// Zod validation schemas
import { z } from 'zod';

export const contactSchema = z.object({
  name: z
    .string()
    .min(2, 'Nome deve ter no mínimo 2 caracteres')
    .max(100, 'Nome deve ter no máximo 100 caracteres')
    .regex(/^[a-zA-ZÀ-ÿ\s]+$/, 'Nome deve conter apenas letras'),

  email: z.string().email('Email inválido').max(255, 'Email deve ter no máximo 255 caracteres'),

  message: z
    .string()
    .min(10, 'Mensagem deve ter no mínimo 10 caracteres')
    .max(1000, 'Mensagem deve ter no máximo 1000 caracteres'),
});

export type ContactFormData = z.infer<typeof contactSchema>;
