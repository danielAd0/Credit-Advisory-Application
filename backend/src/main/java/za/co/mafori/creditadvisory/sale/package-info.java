/**
 * Sales typed into the app on the Consultant actions page (docs/api-sales.md).
 *
 * <p>
 * The same sales also arrive in the weekly spreadsheet, and the spreadsheet
 * wins: importing a week replaces that week's typed-in sales (V21,
 * WeeklyImportService.confirm), so nothing is counted twice.
 */
package za.co.mafori.creditadvisory.sale;
