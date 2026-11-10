import * as admin from './admin';
import * as mockCatalogue from './mockCatalogue';

export type { Employer, Product, ProductQuotation } from './admin';

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
  listProductQuotations: admin.listProductQuotations,
  createProductQuotation: admin.createProductQuotation,
  updateProductQuotation: admin.updateProductQuotation,
  setProductQuotationActive: admin.setProductQuotationActive,
};

export const catalogue = useMock ? mockCatalogue : real;
