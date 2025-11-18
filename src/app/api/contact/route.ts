// Contact API route - POST endpoint com validação e sanitização
import { NextRequest, NextResponse } from 'next/server';
import DOMPurify from 'isomorphic-dompurify';
import { contactSchema } from '@/lib/validations';

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();

    // Validação com Zod
    const validationResult = contactSchema.safeParse(body);

    if (!validationResult.success) {
      return NextResponse.json(
        {
          error: 'Dados inválidos',
          details: validationResult.error.issues,
        },
        { status: 400 }
      );
    }

    const { name, email, message } = validationResult.data;

    // Sanitização XSS com DOMPurify
    const sanitizedData = {
      name: DOMPurify.sanitize(name),
      email: DOMPurify.sanitize(email),
      message: DOMPurify.sanitize(message),
    };

    // Aqui você enviaria o email (Nodemailer, SendGrid, Resend, etc.)
    // Por enquanto, apenas log no console
    // eslint-disable-next-line no-console
    console.log('Contact form submission:', sanitizedData);

    // Simular delay de envio
    await new Promise(resolve => setTimeout(resolve, 1000));

    return NextResponse.json(
      {
        success: true,
        message: 'Mensagem enviada com sucesso!',
      },
      { status: 200 }
    );
  } catch (error) {
    // eslint-disable-next-line no-console
    console.error('Contact API error:', error);
    return NextResponse.json(
      {
        error: 'Erro interno do servidor',
      },
      { status: 500 }
    );
  }
}

// Method Not Allowed para outros métodos
export async function GET() {
  return NextResponse.json(
    {
      error: 'Method not allowed',
    },
    { status: 405 }
  );
}
