import type { Employer } from '../api';

export interface Form {
  name: string;
  company: string;
  employerType: string;
}

export const EMPTY: Form = { name: '', company: '', employerType: '' };

export const toForm = (e: Employer): Form => ({
  name: e.name,
  company: e.company ?? '',
  employerType: e.employerType ?? '',
});

export const employerFields = (v: Form) => ({
  name: v.name.trim(),
  company: v.company.trim() || null,
  employerType: v.employerType.trim() || null,
});
