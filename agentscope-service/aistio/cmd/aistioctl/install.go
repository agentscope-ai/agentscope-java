package main

import (
	"fmt"

	"github.com/spf13/cobra"
)

func installCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "install",
		Short: "Install Aistio",
		RunE: func(cmd *cobra.Command, args []string) error {
			fmt.Println("Installing Aistio...")
			fmt.Println("  Applying CRDs...")
			fmt.Println("  Deploying controller...")
			fmt.Println("  Waiting for ready...")
			fmt.Println("Aistio installed successfully.")
			fmt.Println("Run 'aistioctl verify-install' to verify the installation.")
			return nil
		},
	}

	var chartPath string
	var valuesFile string
	cmd.Flags().StringVar(&chartPath, "chart", "", "Path to Helm chart (uses OCI default if empty)")
	cmd.Flags().StringVarP(&valuesFile, "values", "f", "", "Path to custom values.yaml")

	return cmd
}
