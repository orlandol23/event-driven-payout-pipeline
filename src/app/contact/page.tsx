// Contact page - Server-Side Rendering
'use client';

import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { contactSchema, ContactFormData } from '@/lib/validations';
import Input from '@/components/ui/Input';
import Textarea from '@/components/ui/Textarea';
import Button from '@/components/ui/Button';

export default function ContactPage() {
  const [submitStatus, setSubmitStatus] = useState<'idle' | 'loading' | 'success' | 'error'>(
    'idle'
  );

  const {
    register,
    handleSubmit,
    formState: { errors },
    reset,
  } = useForm<ContactFormData>({
    resolver: zodResolver(contactSchema),
  });

  const onSubmit = async (data: ContactFormData) => {
    setSubmitStatus('loading');

    try {
      const res = await fetch('/api/contact', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
      });

      if (!res.ok) {
        throw new Error('Failed to send message');
      }

      setSubmitStatus('success');
      reset();

      // Reset status após 5 segundos
      setTimeout(() => {
        setSubmitStatus('idle');
      }, 5000);
    } catch (error) {
      setSubmitStatus('error');

      // Reset status após 5 segundos
      setTimeout(() => {
        setSubmitStatus('idle');
      }, 5000);
    }
  };

  return (
    <div className="container mx-auto px-4 py-12">
      <div className="max-w-2xl mx-auto">
        <h1 className="text-4xl md:text-5xl font-bold text-gray-900 mb-4">Entre em Contato</h1>
        <p className="text-xl text-gray-600 mb-8">
          Tem alguma pergunta ou proposta? Ficarei feliz em conversar! Preencha o formulário abaixo
          e responderei o mais breve possível.
        </p>

        <div className="bg-white rounded-lg shadow-md p-8">
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-6">
            <Input
              id="name"
              label="Nome"
              type="text"
              placeholder="Seu nome completo"
              error={errors.name?.message}
              {...register('name')}
            />

            <Input
              id="email"
              label="Email"
              type="email"
              placeholder="seu.email@exemplo.com"
              error={errors.email?.message}
              {...register('email')}
            />

            <Textarea
              id="message"
              label="Mensagem"
              rows={6}
              placeholder="Digite sua mensagem aqui..."
              error={errors.message?.message}
              {...register('message')}
            />

            <Button
              type="submit"
              isLoading={submitStatus === 'loading'}
              disabled={submitStatus === 'loading'}
              className="w-full"
            >
              {submitStatus === 'loading' ? 'Enviando...' : 'Enviar Mensagem'}
            </Button>

            {submitStatus === 'success' && (
              <div className="bg-green-50 border border-green-200 text-green-800 px-4 py-3 rounded-lg">
                ✓ Mensagem enviada com sucesso! Responderei em breve.
              </div>
            )}

            {submitStatus === 'error' && (
              <div className="bg-red-50 border border-red-200 text-red-800 px-4 py-3 rounded-lg">
                ✗ Erro ao enviar mensagem. Por favor, tente novamente.
              </div>
            )}
          </form>
        </div>

        <div className="mt-12 bg-blue-50 rounded-lg p-6">
          <h2 className="text-2xl font-bold text-gray-900 mb-4">Outras formas de contato</h2>
          <div className="space-y-3 text-gray-700">
            <p className="flex items-center gap-2">
              <span className="font-medium">Email:</span>
              <a href="mailto:orlando@example.com" className="text-blue-600 hover:underline">
                orlando@example.com
              </a>
            </p>
            <p className="flex items-center gap-2">
              <span className="font-medium">LinkedIn:</span>
              <a
                href="https://linkedin.com/in/orlando-fernandes"
                target="_blank"
                rel="noopener noreferrer"
                className="text-blue-600 hover:underline"
              >
                /in/orlando-fernandes
              </a>
            </p>
            <p className="flex items-center gap-2">
              <span className="font-medium">GitHub:</span>
              <a
                href="https://github.com/orlandol23"
                target="_blank"
                rel="noopener noreferrer"
                className="text-blue-600 hover:underline"
              >
                @orlandol23
              </a>
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
