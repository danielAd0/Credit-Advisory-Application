import * as admin from './admin';
import * as mockCatalogue from './mockCatalogue';

export type {
  Employer,
  EmployerDuplicate,
  EmployerDuplicateGroup,
  EmployerDuplicateSuggestions,
  MergeEmployerResult,
  MergeGroupResult,
  Product,
  ProductQuotation,
} from './admin';

const useMock = import.meta.env.VITE_USE_MOCK === 'true';

const real = {
  listProducts: admin.listProducts,
  createProduct: admin.createProduct,
  updateProduct: admin.updateProduct,
  setProductActive: admin.setProductActive,
  listEmployers: admin.listEmployers,
  createEmployer: admin.createEmployer,
  updateEmployer: admin.updateEmployer,
  setEmployerActive: admin.setEmployerActive,
  mergeEmployer: admin.mergeEmployer,
  keepEmployer: admin.keepEmployer,
  duplicateSuggestions: admin.duplicateSuggestions,
  mergeEmployerGroup: admin.mergeEmployerGroup,
  listProductQuotations: admin.listProductQuotations,
  createProductQuotation: admin.createProductQuotation,
  updateProductQuotation: admin.updateProductQuotation,
  setProductQuotationActive: admin.setProductQuotationActive,
};

export const catalogue = useMock ? mockCatalogue : real;
