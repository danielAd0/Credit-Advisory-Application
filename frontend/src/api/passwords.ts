const MIN_LENGTH = 12;
const MAX_BYTES = 72;

export function passwordProblem(password: string, email = ''): string | null {
  if (password.length < MIN_LENGTH) return `Use at least ${MIN_LENGTH} characters.`;
  if (new TextEncoder().encode(password).length > MAX_BYTES) return 'That password is too long.';
  if (email && password.toLowerCase() === email.trim().toLowerCase())
    return 'The password cannot be the email address.';
  return null;
}

export function generatePassword(length = 16): string {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789';
  const bytes = crypto.getRandomValues(new Uint8Array(length));
  return Array.from(bytes, (b) => alphabet[b % alphabet.length]).join('');
}
