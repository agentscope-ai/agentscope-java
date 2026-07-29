package main

import (
	"fmt"

	"github.com/spf13/cobra"
)

func verifyCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "verify-install",
		Short: "Verify Aistio installation",
		RunE: func(cmd *cobra.Command, args []string) error {
			fmt.Println("Verifying Aistio installation...")

			checks := []struct {
				name string
				fn   func() error
			}{
				{"CRDs installed", checkCRDs},
				{"Controller running", checkController},
				{"REST API reachable", checkAPI},
			}

			allPassed := true
			for _, check := range checks {
				err := check.fn()
				if err != nil {
					fmt.Printf("  x %s: %v\n", check.name, err)
					allPassed = false
				} else {
					fmt.Printf("  ok %s\n", check.name)
				}
			}

			if !allPassed {
				return fmt.Errorf("verification failed")
			}
			fmt.Println("All checks passed!")
			return nil
		},
	}
}

func checkCRDs() error {
	// TODO: Use discovery client to check CRDs
	return nil
}

func checkController() error {
	// TODO: Check deployment status
	return nil
}

func checkAPI() error {
	client := newAPIClient()
	_, err := client.Get(fmt.Sprintf("%s/api/v1/version", apiEndpoint))
	return err
}
